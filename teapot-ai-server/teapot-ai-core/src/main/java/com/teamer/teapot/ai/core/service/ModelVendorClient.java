package com.teamer.teapot.ai.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型供应商的只读发现接口：模型清单（list）与探活（probe）（SPEC §30.2）。
 *
 * <p>刻意不走各家 SDK：这里要的是一次 GET 就能分清的三件事——密钥对不对、模型在不在、
 * 窗口多大，而 SDK 的 list 能力在各家版本上并不同构（DashScope SDK 至今没有清单类）。
 * 形状与 {@code ModelProviderStrategy} 一家一份保持一致：新增供应商时在 {@link Vendor} 加一行。
 *
 * <p>探活不产生 token 计费：只打清单/详情端点，不发补全请求。
 */
@Slf4j
@Component
public class ModelVendorClient {

    /**
     * 探活结果。{@code contextWindowSize == 0} 表示该供应商的接口不给出窗口，不代表模型不可用；
     * {@code inputModalities} 为空表示该供应商不报模态，能力位维持原样不动。
     */
    public record ProbeResult(boolean ok, int httpStatus, int contextWindowSize,
                              List<String> inputModalities, String message) {

        static ProbeResult unsupported(String provider) {
            return new ProbeResult(false, 0, 0, List.of(), provider + " 暂不支持探活");
        }

        static ProbeResult of(boolean ok, int status, int window, String message) {
            return new ProbeResult(ok, status, window, List.of(), message);
        }
    }

    /**
     * 各供应商的两个只读端点（OpenAI 形状那几家）。
     *
     * @param listUrl    清单端点（{@code {base}} 处填生效网关或官方默认）
     * @param detailUrl  单模型详情端点，{@code {model}} 处填模型名；空串 = 该供应商没有详情端点，
     *                   探活退化为「在清单里查名字」
     * @param windowPaths 详情响应里窗口值的候选 JSON 路径，按序取第一个正整数
     */
    private record Vendor(String listUrl, String detailUrl, String... windowPaths) {

        private static final Map<String, Vendor> VENDORS = Map.of(
                // OpenAI 兼容网关：路径挂在生效 baseUrl 下，各家自建网关同形
                "openai", new Vendor(
                        "{base}/models", "{base}/models/{model}", "context_window", "max_input_tokens"),
                "openai-official", new Vendor(
                        "{base}/models", "{base}/models/{model}", "context_window", "max_input_tokens"),
                "anthropic", new Vendor(
                        "https://api.anthropic.com/v1/models", "https://api.anthropic.com/v1/models/{model}",
                        "context_window", "context_window.tokens"),
                // Gemini 不给总窗口，inputTokenLimit 是最接近的口径（略小于真实窗口）
                "gemini", new Vendor(
                        "https://generativelanguage.googleapis.com/v1beta/models",
                        "https://generativelanguage.googleapis.com/v1beta/models/{model}",
                        "inputTokenLimit"),
                // Ollama 只有清单（/api/tags），无 GET 详情；探活退化为查清单
                "ollama", new Vendor("{base}/api/tags", ""));

        static Vendor of(String provider) {
            return VENDORS.get(provider);
        }
    }

    /**
     * DashScope 走原生 openapi 而不是 compatible-mode：后者只有请求默认参数袋
     * （{@code extra_info.default_envs} 里 max_tokens 是<b>默认输出上限</b>，与 num_beams/seed 同袋），
     * 原生 {@code model_info.context_window} 才是真窗口，且额外给 {@code inference_metadata.request_modality}
     * ——能力位不必靠模型名猜（实测 2026-10-27：qwen3.8-max 真窗口 1,000,000，
     * 而 compatible-mode 的 max_tokens 报 131072，即其 max_output_tokens）。
     */
    private static final String DASHSCOPE_NATIVE_LIST = "https://dashscope.aliyuncs.com/api/v1/models";
    private static final int DASHSCOPE_PAGE_SIZE = 200;
    /** 原生 request_modality → 我们的能力位（Text 是基线，不入能力位） */
    private static final Map<String, String> MODALITY_TO_CAPABILITY =
            Map.of("Image", "image", "Video", "video", "Audio", "audio");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DASHSCOPE = "dashscope";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;
    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;
    @Value("${OPENAI_BASE_URL:}")
    private String openaiBaseUrl;
    @Value("${ANTHROPIC_API_KEY:}")
    private String anthropicApiKey;
    @Value("${GEMINI_API_KEY:}")
    private String geminiApiKey;
    @Value("${OLLAMA_BASE_URL:}")
    private String ollamaBaseUrl;

    /** 清单缓存：供应商 + 生效网关 → 模型名列表（列表庞大且变动低频） */
    private final Map<String, CacheEntry> listCache = new ConcurrentHashMap<>();

    private record CacheEntry(List<String> models, long at) {}

    /**
     * 该供应商是否能自己报出「输入模态 + 上下文窗口」。能报的一律不让人手填
     * （SPEC §30.6）：保存模型入口 / Agent 自带模型时以探活结果为权威覆盖这两项。
     *
     * <p>目前只有 DashScope（原生目录 {@code model_info.context_window} 与
     * {@code inference_metadata.request_modality}）。OpenAI 的清单不报模态，
     * 故不在此列——它的 baseUrl 由用户自定，放开还会把服务端指向任意地址。
     */
    public boolean supportsDiscovery(String provider) {
        return DASHSCOPE.equals(provider);
    }

    /** 某供应商的在售模型清单；密钥取「本次请求自带 &gt; 环境变量」，与建模型实例的定序一致。 */
    public List<String> listModels(String provider, String baseUrl, String apiKey) {
        boolean nativeDashscope = DASHSCOPE.equals(provider);
        Vendor vendor = Vendor.of(provider);
        if (!nativeDashscope && vendor == null) {
            return List.of();
        }
        String key = provider + "|"
                + (nativeDashscope ? "native" : effectiveBaseUrl(vendor, provider, baseUrl));
        CacheEntry cached = listCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return cached.models();
        }
        List<String> models = nativeDashscope
                ? fetchDashscopeModels(requireKey(provider, apiKey, baseUrl))
                : fetchModels(vendor, provider, apiKey, baseUrl);
        listCache.put(key, new CacheEntry(models, System.currentTimeMillis()));
        return models;
    }

    /**
     * 探活：确认密钥可用且模型存在，顺带读回上下文窗口与输入模态。
     *
     * <p>不抛异常——探活的意义正是把「错在哪」如实报给界面，各家端点的状态码语义
     * （401 密钥错、404 模型不存在）直接映射成文案。
     */
    public ProbeResult probe(String provider, String modelName, String baseUrl, String apiKey) {
        if (DASHSCOPE.equals(provider)) {
            return probeDashscope(modelName, requireKey(provider, apiKey, baseUrl));
        }
        Vendor vendor = Vendor.of(provider);
        if (vendor == null) {
            return ProbeResult.unsupported(provider);
        }
        String key = requireKey(provider, apiKey, baseUrl);
        if (vendor.detailUrl().isBlank()) {
            // 没有详情端点：退化为「模型是否出现在清单里」，窗口无从得知
            List<String> models = fetchModels(vendor, provider, apiKey, baseUrl);
            boolean found = models.stream().anyMatch(m -> m.equalsIgnoreCase(modelName));
            return ProbeResult.of(found, found ? 200 : 404, 0,
                    found ? "清单中已找到该模型（该供应商不提供窗口大小）" : "该供应商清单中未找到 " + modelName);
        }
        String url = vendor.detailUrl()
                .replace("{base}", effectiveBaseUrl(vendor, provider, baseUrl))
                .replace("{model}", modelName);
        try {
            HttpResponse<String> resp = send(url, provider, key);
            if (resp.statusCode() != 200) {
                return ProbeResult.of(false, resp.statusCode(), 0, describe(resp));
            }
            int window = firstPositive(JSON.readTree(resp.body()), vendor.windowPaths());
            return ProbeResult.of(true, 200, window,
                    window > 0 ? "可用，上下文窗口 " + window + " tokens" : "可用，但该接口不给出窗口大小");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("探活异常 provider={} model={}", provider, modelName, e);
            return ProbeResult.of(false, 0, 0, "请求失败：" + e.getMessage());
        }
    }

    /**
     * DashScope 探活：原生目录没有「按名取详情」的端点，用 {@code ?name=} 精确过滤代替；
     * 未命中返回的是 200 + 空列表而非 404，所以「模型不存在」要靠列表空来判。
     */
    private ProbeResult probeDashscope(String modelName, String apiKey) {
        try {
            HttpResponse<String> resp = send(
                    DASHSCOPE_NATIVE_LIST + "?name=" + enc(modelName) + "&page_size=1", DASHSCOPE, apiKey);
            if (resp.statusCode() != 200) {
                return ProbeResult.of(false, resp.statusCode(), 0, describe(resp));
            }
            for (JsonNode m : JSON.readTree(resp.body()).path("output").path("models")) {
                if (!modelName.equals(m.path("model").asText())) {
                    continue;
                }
                int window = m.path("model_info").path("context_window").asInt(0);
                List<String> modalities = requestModalities(m);
                return new ProbeResult(true, 200, window, modalities,
                        (window > 0 ? "可用，上下文窗口 " + window + " tokens" : "可用，该模型未报窗口")
                                + (modalities.isEmpty() ? "" : "，输入模态 " + String.join("/", modalities)));
            }
            return ProbeResult.of(false, 200, 0,
                    "DashScope 目录中无此模型（可能已下线或名称不符）：" + modelName);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DashScope 探活异常 model={}", modelName, e);
            return ProbeResult.of(false, 0, 0, "请求失败：" + e.getMessage());
        }
    }

    /** 原生清单分页拉全（实测 total=359，page_size 200 两页）；各家 page_size 上限不一，取保守值。 */
    private List<String> fetchDashscopeModels(String apiKey) {
        List<String> ids = new ArrayList<>();
        try {
            for (int pageNo = 1; ; pageNo++) {
                HttpResponse<String> resp = send(DASHSCOPE_NATIVE_LIST + "?page_no=" + pageNo
                        + "&page_size=" + DASHSCOPE_PAGE_SIZE, DASHSCOPE, apiKey);
                if (resp.statusCode() != 200) {
                    throw new BizException("DashScope 模型清单拉取失败：" + describe(resp));
                }
                JsonNode models = JSON.readTree(resp.body()).path("output").path("models");
                for (JsonNode m : models) {
                    String id = m.path("model").asText("");
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
                if (models.isEmpty() || ids.size() >= respTotal(resp)) {
                    break;
                }
            }
            ids.sort(Comparator.naturalOrder());
            return ids;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DashScope 模型清单拉取异常", e);
            throw new BizException("DashScope 模型清单拉取失败：" + e.getMessage());
        }
    }

    private int respTotal(HttpResponse<String> resp) throws Exception {
        return JSON.readTree(resp.body()).path("output").path("total").asInt(0);
    }

    /** 原生 {@code inference_metadata.request_modality} → 我们的能力位（Text 是基线，不计入） */
    private List<String> requestModalities(JsonNode model) {
        List<String> caps = new ArrayList<>();
        for (JsonNode raw : model.path("inference_metadata").path("request_modality")) {
            String cap = MODALITY_TO_CAPABILITY.get(raw.asText());
            if (cap != null && !caps.contains(cap)) {
                caps.add(cap);
            }
        }
        return caps;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<String> fetchModels(Vendor vendor, String provider, String apiKey, String baseUrl) {
        String key = requireKey(provider, apiKey, baseUrl);
        try {
            HttpResponse<String> resp = send(
                    vendor.listUrl().replace("{base}", effectiveBaseUrl(vendor, provider, baseUrl)),
                    provider, key);
            if (resp.statusCode() != 200) {
                throw new BizException("模型清单拉取失败：" + describe(resp));
            }
            List<String> ids = extractModelIds(JSON.readTree(resp.body()));
            ids.sort(Comparator.naturalOrder());
            return ids;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("模型清单拉取异常 provider={}", provider, e);
            throw new BizException("模型清单拉取失败：" + e.getMessage());
        }
    }

    /** 兼容三种响应形状：{@code {data:[{id}]}}（OpenAI 系）、{@code {models:[{name}]}}（Gemini）、{@code {models:[str]}}（Ollama）。 */
    private List<String> extractModelIds(JsonNode root) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : firstArray(root, "data", "models")) {
            String id = node.isTextual() ? node.asText()
                    : firstNonBlank(node.path("id").asText(""), node.path("name").asText(""));
            if (!id.isBlank()) {
                // Gemini 的 name 形如 "models/gemini-2.5-pro"，去掉前缀与各家命名空间保持一致形状
                ids.add(id.startsWith("models/") ? id.substring("models/".length()) : id);
            }
        }
        return ids;
    }

    private List<JsonNode> firstArray(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isArray()) {
                List<JsonNode> out = new ArrayList<>();
                node.forEach(out::add);
                return out;
            }
        }
        return List.of();
    }

    private int firstPositive(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            for (String segment : path.split("\\.")) {
                node = node.path(segment);
            }
            if (node.isNumber() && node.asInt() > 0) {
                return node.asInt();
            }
        }
        return 0;
    }

    private HttpResponse<String> send(String url, String provider, String apiKey) throws Exception {
        // Gemini 的 REST 只认 ?key=（各家 SDK 同款），其余走 Bearer；Ollama 本地无鉴权
        if ("gemini".equals(provider) && !apiKey.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET();
        if (!"ollama".equals(provider) && !"gemini".equals(provider) && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 各家状态码文案：401/403 归到密钥，404 归到模型名，其余原样带回响应体摘要。 */
    private String describe(HttpResponse<String> resp) {
        String body = resp.body() == null ? "" : resp.body();
        return switch (resp.statusCode()) {
            case 401, 403 -> "密钥无效或被拒（HTTP " + resp.statusCode() + "）：" + brief(body);
            case 404 -> "模型不存在（HTTP 404）：" + brief(body);
            default -> "HTTP " + resp.statusCode() + "：" + brief(body);
        };
    }

    private String brief(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** 生效网关：请求自带 &gt; 环境变量；OpenAI 系与 Ollama 的端点挂在网关下，缺了要报错。 */
    private String effectiveBaseUrl(Vendor vendor, String provider, String baseUrl) {
        String configured = switch (provider) {
            case "openai", "openai-official" -> openaiBaseUrl;
            case "ollama" -> ollamaBaseUrl;
            default -> "";
        };
        String base = firstNonBlank(baseUrl, configured);
        if (base.isBlank()) {
            base = switch (provider) {
                case "openai", "openai-official" -> "https://api.openai.com/v1";
                case "ollama" -> "http://localhost:11434";
                default -> "";
            };
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        boolean needsBase = vendor.listUrl().contains("{base}");
        if (needsBase && base.isBlank()) {
            throw new BizException(provider + " 未配置网关地址，无法访问模型清单");
        }
        return base;
    }

    private String requireKey(String provider, String apiKey, String baseUrl) {
        if ("ollama".equals(provider)) {
            return "";
        }
        String env = switch (provider) {
            case "dashscope" -> dashscopeApiKey;
            case "openai", "openai-official" -> openaiApiKey;
            case "anthropic" -> anthropicApiKey;
            case "gemini" -> geminiApiKey;
            default -> "";
        };
        String key = firstNonBlank(apiKey, env);
        if (key.isBlank()) {
            throw new BizException("未配置该供应商的 API Key，无法探活/拉取清单");
        }
        return key;
    }

    /** DashScope 清单的历史口径：只留 qwen 系文本对话模型（SPEC §6.4） */
    public List<String> filterChatModels(List<String> models, String provider) {
        if (!"dashscope".equals(provider)) {
            return models;
        }
        return models.stream()
                .filter(id -> {
                    String lower = id.toLowerCase(Locale.ROOT);
                    return lower.startsWith("qwen")
                            && !lower.contains("image") && !lower.contains("asr") && !lower.contains("realtime");
                })
                .toList();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
