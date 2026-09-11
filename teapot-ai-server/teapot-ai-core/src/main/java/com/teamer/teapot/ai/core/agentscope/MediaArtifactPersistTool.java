package com.teamer.teapot.ai.core.agentscope;

import com.teamer.teapot.ai.core.storage.ImageStorageRouter;
import com.teamer.teapot.ai.core.storage.StoredImage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生成产物转存装饰器（SPEC-media-gen §4.9）：包一层 DashScope 生成工具，
 * 把产物从「百炼临时链接（约 24h 失效）」落到 Agent 自己的存储载体上
 * （feature.storage，SPEC §22.1），会话历史里存的即长期直链，隔天回看不再裂图。
 *
 * <p>为什么做在工具层而不是中间件层：MiddlewareBase 只有 onAgent/onReasoning/onActing/onModelCall/
 * onSystemPrompt 五个钩子，没有「工具结果后处理」钩子；onActing 拿到的是事件流，工具结果按 delta
 * 分片在其中，改写脆弱易碎。工具层装饰同名覆盖（ToolRegistry 用 map.put）是最低成本的落点。
 *
 * <p>只在生效载体为 oss 时替换：base64 载体下 store() 返回 data URL，几十 MB 的产物内联进消息体
 * 会直接撑爆上下文，因此 base64 场景保持原样返回临时链接。任何一步失败也保持原样（warn 一次），
 * 生成结果不该被转存失败拖掉。
 */
@Slf4j
public class MediaArtifactPersistTool extends ToolBase {

    /** 产物体积上限（对齐 ChatVideoController）：超限不下载，避免服务端内存被视频打爆 */
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

    private final AgentTool delegate;
    private final String agentKey;
    private final ImageStorageRouter storageRouter;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public MediaArtifactPersistTool(AgentTool delegate, String agentKey, ImageStorageRouter storageRouter) {
        super(ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                .concurrencySafe(true));
        this.delegate = delegate;
        this.agentKey = agentKey;
        this.storageRouter = storageRouter;
    }

    /** schema 相关属性全部透传，装饰只影响执行结果，不改工具对模型的呈现 */
    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param)
                .publishOn(Schedulers.boundedElastic())
                .map(this::persistArtifacts);
    }

    /** 下载产物并转存；返回替换过 URL 的结果，无需/无法转存时原样返回 */
    private ToolResultBlock persistArtifacts(ToolResultBlock result) {
        if (result == null || result.getOutput().isEmpty()) {
            return result;
        }
        if (!"oss".equals(storageRouter.effectiveStrategy(agentKey))) {
            // base64 载体：保持临时链接（见类注释）；存量 Agent 未配 storage 也走这条判断
            return result;
        }
        List<ContentBlock> output = result.getOutput();
        List<ContentBlock> rewritten = null;
        for (int i = 0; i < output.size(); i++) {
            ContentBlock block = output.get(i);
            ContentBlock persisted = persistBlock(block);
            if (persisted == block) {
                continue;
            }
            if (rewritten == null) {
                rewritten = new ArrayList<>(output);
            }
            rewritten.set(i, persisted);
        }
        if (rewritten == null) {
            return result;
        }
        return ToolResultBlock.builder()
                .id(result.getId())
                .name(result.getName())
                .output(rewritten)
                .metadata(result.getMetadata())
                .state(result.getState())
                .build();
    }

    /** 单个媒体块转存；不需要转存或失败时返回原块 */
    private ContentBlock persistBlock(ContentBlock block) {
        String url = remoteUrlOf(block);
        if (url == null) {
            return block;
        }
        String kind = kindOf(block);
        try {
            HttpResponse<byte[]> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                log.warn("生成产物下载失败 status={} url={}", resp.statusCode(), trim(url));
                return block;
            }
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) {
                return block;
            }
            if (bytes.length > MAX_BYTES) {
                log.warn("生成产物超出转存体积上限（{} 字节），保留原始临时链接 url={}", MAX_BYTES, trim(url));
                return block;
            }
            String mediaType = mediaTypeOf(resp.headers().firstValue("content-type").orElse(null), kind);
            StoredImage stored = storageRouter.store(bytes, mediaType, agentKey);
            if (stored == null || !"oss".equals(stored.strategy()) || stored.url() == null) {
                return block;
            }
            log.info("生成产物已转存 OSS agentKey={} bytes={} type={} from={} to={}",
                    agentKey, bytes.length, mediaType, trim(url), stored.url());
            return rebuild(block, stored.url());
        } catch (Exception e) {
            // 转存失败不影响生成结果：临时链接仍在，用户当轮能看到图
            log.warn("生成产物转存失败，保留原始临时链接 agentKey={} url={}", agentKey, trim(url), e);
            return block;
        }
    }

    /** 块的公网可下载链接；非媒体块 / 非 http(s) 源 / base64 源返回 null */
    private static String remoteUrlOf(ContentBlock block) {
        URLSource source = null;
        if (block instanceof ImageBlock image && image.getSource() instanceof URLSource us) {
            source = us;
        } else if (block instanceof VideoBlock video && video.getSource() instanceof URLSource us) {
            source = us;
        } else if (block instanceof AudioBlock audio && audio.getSource() instanceof URLSource us) {
            source = us;
        }
        if (source == null || source.getUrl() == null) {
            return null;
        }
        String lower = source.getUrl().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? source.getUrl() : null;
    }

    private static String kindOf(ContentBlock block) {
        if (block instanceof VideoBlock) {
            return "video";
        }
        return block instanceof AudioBlock ? "audio" : "image";
    }

    /** Content-Type 缺失或不可识别时按产物类型兜底（否则 OSS key 后缀会落成 .bin） */
    private static String mediaTypeOf(String contentType, String kind) {
        if (contentType != null) {
            int semicolon = contentType.indexOf(';');
            String pure = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
            if (pure.contains("/")) {
                return "image/jpg".equals(pure) ? "image/jpeg" : pure;
            }
        }
        return switch (kind) {
            case "video" -> "video/mp4";
            case "audio" -> "audio/wav";
            default -> "image/png";
        };
    }

    /** 换 URL 重建同类型块，其余属性原样保留 */
    private static ContentBlock rebuild(ContentBlock block, String url) {
        URLSource source = URLSource.builder().url(url).build();
        if (block instanceof ImageBlock image) {
            return ImageBlock.builder().source(source)
                    .minPixels(image.getMinPixels()).maxPixels(image.getMaxPixels()).build();
        }
        if (block instanceof VideoBlock video) {
            return VideoBlock.builder().source(source)
                    .fps(video.getFps()).maxFrames(video.getMaxFrames())
                    .minPixels(video.getMinPixels()).maxPixels(video.getMaxPixels())
                    .totalPixels(video.getTotalPixels()).build();
        }
        return AudioBlock.builder().source(source).build();
    }

    /** 日志里不刷完整签名链接（百炼 URL 带 token） */
    private static String trim(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query) + "?…";
    }
}
