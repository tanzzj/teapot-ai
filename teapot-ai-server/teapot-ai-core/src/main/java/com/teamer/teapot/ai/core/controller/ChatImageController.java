package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.storage.ImageStorageRouter;
import com.teamer.teapot.ai.core.storage.StoredImage;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 图片上传接口（SPEC §20.5/§22.1）：multipart 直传，按 Agent feature.storage 路由 base64 / OSS 记录。
 * 服务端复检体积/MIME/magic number，防绕过前端限额（§20.8）。
 * 权限：登录用户（RBAC /api/chat/* 覆盖 developer/viewer）。
 */
@RestController
@RequestMapping("/api/chat/image")
public class ChatImageController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> MIME_WHITELIST =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final ImageStorageRouter imageStorageRouter;

    public ChatImageController(ImageStorageRouter imageStorageRouter) {
        this.imageStorageRouter = imageStorageRouter;
    }

    /** 按 Agent 探测生效载体（§22.1）：前端上传链路据此选择 base64 / OSS */
    @GetMapping("/strategy")
    public Result<Map<String, String>> strategy(@RequestParam(value = "agentKey", required = false) String agentKey) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("strategy", imageStorageRouter.effectiveStrategy(agentKey));
        return Result.ok(body);
    }

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "agentKey", required = false) String agentKey) {
        if (file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("图片超过 5MB 限制");
        }
        String mediaType = normalizeMediaType(file.getContentType());
        if (mediaType == null) {
            throw new BizException("不支持的图片类型，仅支持 JPEG/PNG/WebP/GIF");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }
        if (!magicMatches(data, mediaType)) {
            throw new BizException("文件内容与声明的图片类型不符");
        }
        StoredImage stored = imageStorageRouter.store(data, mediaType, agentKey);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", stored.url());
        body.put("strategy", stored.strategy());
        return Result.ok(body);
    }

    private static String normalizeMediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String mt = contentType.toLowerCase().split(";")[0].trim();
        return MIME_WHITELIST.contains(mt) ? mt : null;
    }

    /** magic number 校验：JPEG / PNG / WEBP / GIF */
    private static boolean magicMatches(byte[] data, String mediaType) {
        if (data.length < 12) {
            return false;
        }
        return switch (mediaType) {
            case "image/jpeg" -> (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
            case "image/png" -> (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
            case "image/webp" -> data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                    && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
            case "image/gif" -> data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8';
            default -> false;
        };
    }
}
