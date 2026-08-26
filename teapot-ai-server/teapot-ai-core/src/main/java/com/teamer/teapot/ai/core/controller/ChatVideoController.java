package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.storage.ImageStorageRouter;
import com.teamer.teapot.ai.core.storage.StoredImage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 对话台视频附件上传（多模态视频输入）：链路与图片上传对称——
 * 按 Agent 探测生效载体（base64 内联 / OSS 直链），响应附带 mediaType，
 * 供前端在 OSS 链路下仍能为 AG-UI video part 标注准确 MIME。
 * 限额 30MB（base64 模式膨胀后 ≈40MB，在 nginx client_max_body_size 60m 内留余量；
 * base64 模式视频随 AG-UI 请求体传输，体积换零依赖，OSS 可用时建议对视频场景启用 OSS）。
 */
@RestController
@RequestMapping("/api/chat/video")
public class ChatVideoController {

    private static final long MAX_BYTES = 30L * 1024 * 1024;
    private static final Set<String> MIME_WHITELIST =
            Set.of("video/mp4", "video/webm", "video/quicktime", "video/x-matroska");

    private final ImageStorageRouter imageStorageRouter;

    public ChatVideoController(ImageStorageRouter imageStorageRouter) {
        this.imageStorageRouter = imageStorageRouter;
    }

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "agentKey", required = false) String agentKey) {
        if (file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("视频超过 30MB 限制");
        }
        String mediaType = normalizeMediaType(file.getContentType());
        if (mediaType == null) {
            throw new BizException("不支持的视频类型，仅支持 MP4/WebM/MOV/MKV");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }
        if (!magicMatches(data, mediaType)) {
            throw new BizException("文件内容与声明的视频类型不符");
        }
        StoredImage stored = imageStorageRouter.store(data, mediaType, agentKey);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", stored.url());
        body.put("strategy", stored.strategy());
        body.put("mediaType", mediaType);
        return Result.ok(body);
    }

    private static String normalizeMediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String mt = contentType.toLowerCase().split(";")[0].trim();
        return MIME_WHITELIST.contains(mt) ? mt : null;
    }

    /** magic number 校验：MP4(ftyp) / WebM(MKV EBML) / MOV(ftyp) / MKV(同 EBML) */
    private static boolean magicMatches(byte[] data, String mediaType) {
        if (data.length < 12) {
            return false;
        }
        return switch (mediaType) {
            case "video/mp4", "video/quicktime" ->
                // ISO BMFF：4 字节长度 + 'ftyp'（ftyp box 不一定在第 0 字节偏移，但常规文件均在头部）
                    data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
            case "video/webm", "video/x-matroska" ->
                // EBML 头 magic：0x1A45DFA3
                    (data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                            && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3;
            default -> false;
        };
    }
}
