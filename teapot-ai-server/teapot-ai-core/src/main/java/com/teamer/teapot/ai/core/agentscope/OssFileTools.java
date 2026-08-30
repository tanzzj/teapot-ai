package com.teamer.teapot.ai.core.agentscope;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.storage.OssClientManager;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OSS 文件上传/下载工具（Agent 工具，runtime.enableOssFile 开关挂载，见 {@link OssToolMiddleware}）：
 * 复用平台现有 OSS 接入（OssClientManager + OssConnection 全局激活记录），
 * 文件路径限定在 Agent 工作区内（防目录穿越），对象级 public-read、bucket 保持私有（§20.8 同款策略）。
 */
@Slf4j
public class OssFileTools {

    /** 单文件上传体积上限（20MB） */
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    /** 单文件下载体积上限（50MB） */
    private static final long MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024;
    /** Agent 文件对象 key 前缀（与对话图片、头像分开存放） */
    private static final String DEFAULT_KEY_PREFIX = "teapot-ai/agent-files/";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 扩展名 → contentType（未知扩展名一律 application/octet-stream） */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"), Map.entry("md", "text/markdown"),
            Map.entry("json", "application/json"), Map.entry("csv", "text/csv"),
            Map.entry("html", "text/html"), Map.entry("xml", "application/xml"),
            Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"),
            Map.entry("mp4", "video/mp4"), Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private final Path workspaceRoot;
    private final OssClientManager ossClientManager;
    private final OssConnection ossConnection;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public OssFileTools(Path workspaceRoot, OssClientManager ossClientManager, OssConnection ossConnection) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.ossClientManager = ossClientManager;
        this.ossConnection = ossConnection;
    }

    /** 上传工作区文件到 OSS，返回可公开访问的直链 */
    @Tool(name = "upload_file",
            description = "Upload a file from the workspace to Alibaba Cloud OSS and return a publicly accessible URL. "
                    + "Use this when the user asks to share/export a file, or needs a download link for a generated file.",
            readOnly = false, concurrencySafe = true)
    public String uploadFile(
            @ToolParam(name = "path", description = "File path relative to the workspace root, e.g. output/report.pdf")
                    String path,
            @ToolParam(name = "contentType", required = false,
                    description = "Optional MIME type, e.g. application/pdf. Inferred from extension when omitted.")
                    String contentType) {
        Path local = resolveInWorkspace(path);
        if (!Files.isRegularFile(local)) {
            throw new BizException("文件不存在：" + path);
        }
        byte[] data;
        try {
            data = Files.readAllBytes(local);
        } catch (Exception e) {
            throw new BizException("读取文件失败：" + e.getMessage());
        }
        if (data.length > MAX_UPLOAD_BYTES) {
            throw new BizException("文件超过上传上限（" + MAX_UPLOAD_BYTES / 1024 / 1024 + "MB）");
        }
        String ext = extOf(local.getFileName().toString());
        String key = keyPrefix() + LocalDate.now().format(DAY) + "/" + UUID.randomUUID()
                + (ext.isEmpty() ? "" : "." + ext);
        String mediaType = contentType != null && !contentType.isBlank()
                ? contentType.trim()
                : CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
        PutObjectRequest request = PutObjectRequest.newBuilder()
                .bucket(ossConnection.getBucket())
                .key(key)
                .objectAcl("public-read")
                .contentType(mediaType)
                .cacheControl("public, max-age=31536000")
                .body(BinaryData.fromBytes(data))
                .build();
        try {
            ossClientManager.get().putObject(request);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("OSS 文件上传失败 key={}", key, e);
            throw new BizException("OSS 文件上传失败：" + e.getMessage());
        }
        String url = publicUrl(key);
        log.info("OSS 文件已上传 path={} key={} bytes={}", path, key, data.length);
        return "已上传。公开访问地址：" + url;
    }

    /** 下载 URL 或 OSS 对象到工作区，返回落地路径与体积 */
    @Tool(name = "download_file",
            description = "Download a file into the workspace. The source can be an http(s) URL or an OSS object key. "
                    + "Use this to fetch external files for further processing.",
            readOnly = false, concurrencySafe = true)
    public String downloadFile(
            @ToolParam(name = "source", description = "An http(s) URL, or an OSS object key within the configured bucket")
                    String source,
            @ToolParam(name = "targetPath", description = "Target file path relative to the workspace root, e.g. downloads/data.csv")
                    String targetPath) {
        if (source == null || source.isBlank()) {
            throw new BizException("source 不能为空");
        }
        Path target = resolveInWorkspace(targetPath);
        String trimmed = source.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            downloadFromUrl(trimmed, target);
        } else {
            downloadFromOss(trimmed, target);
        }
        long size;
        try {
            size = Files.size(target);
        } catch (Exception e) {
            throw new BizException("读取落地文件失败：" + e.getMessage());
        }
        log.info("文件已下载 source={} target={} bytes={}", trimmed, targetPath, size);
        return "已下载到工作区：" + targetPath + "（" + size + " 字节）";
    }

    // ==================== 内部实现 ====================

    /** 相对路径按工作区解析，绝对路径/穿越一律拒绝（仅限工作区内） */
    private Path resolveInWorkspace(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException("文件路径不能为空");
        }
        Path candidate = workspaceRoot.resolve(path.trim()).toAbsolutePath().normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new BizException("文件路径必须位于工作区内：" + path);
        }
        return candidate;
    }

    private void downloadFromUrl(String url, Path target) {
        try {
            HttpResponse<InputStream> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException("下载失败，HTTP " + resp.statusCode() + "：" + url);
            }
            writeStream(resp.body(), target);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("下载失败：" + e.getMessage());
        }
    }

    private void downloadFromOss(String key, Path target) {
        OSSClient client = ossClientManager.get();
        try (GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
                .bucket(ossConnection.getBucket()).key(key).build());
             InputStream body = result.body()) {
            writeStream(body, target);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("OSS 对象下载失败：" + e.getMessage());
        }
    }

    /** 流式落盘，边写边校验体积上限；父目录不存在时自动创建 */
    private void writeStream(InputStream in, Path target) throws Exception {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        long total = 0;
        byte[] buf = new byte[8192];
        try (InputStream src = in; var out = Files.newOutputStream(target)) {
            int n;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new BizException("文件超过下载上限（" + MAX_DOWNLOAD_BYTES / 1024 / 1024 + "MB）");
                }
                out.write(buf, 0, n);
            }
        }
    }

    /** key = {keyPrefix}{yyyyMMdd}/{uuid}.{ext}，UUID 不可枚举（§20.8） */
    private String keyPrefix() {
        String prefix = ossConnection.getKeyPrefix(DEFAULT_KEY_PREFIX);
        if (prefix == null || prefix.isBlank()) {
            prefix = DEFAULT_KEY_PREFIX;
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** 公网直链：customDomain > endpoint（virtual-hosted）> 标准 region 域（同 OssImageStorageStrategy） */
    private String publicUrl(String key) {
        String customDomain = ossConnection.getCustomDomain();
        if (customDomain != null && !customDomain.isBlank()) {
            return stripTrailingSlash(customDomain) + "/" + key;
        }
        String endpoint = ossConnection.getEndpoint();
        String bucket = ossConnection.getBucket();
        if (endpoint != null && !endpoint.isBlank()) {
            String host = endpoint.replaceFirst("^https?://", "");
            return "https://" + bucket + "." + stripTrailingSlash(host) + "/" + key;
        }
        return "https://" + bucket + ".oss-" + ossConnection.getRegion() + ".aliyuncs.com/" + key;
    }

    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
