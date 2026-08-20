package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.teamer.teapot.ai.core.service.AgentService;
import com.teamer.teapot.ai.core.service.StorageConfigService;
import com.teamer.teapot.ai.core.storage.OssImageStorageStrategy;
import com.teamer.teapot.ai.core.storage.StoredImage;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.service.UserService;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 头像上传（SPEC §23）：Agent 头像 + 当前用户头像，固定走 yml 指定的 OSS 记录
 * （teapot.ai.storage.avatar-record，默认 oss-cn-beijing.aliyuncs.com）。
 * 对象 key 带时间戳：换头像后旧 URL 不再被引用，避免 CDN/浏览器缓存旧图。
 * 权限：/api/avatar/* developer（含 admin）；/api/avatar/user 另放开 viewer（仅本人头像）。
 */
@RestController
@RequestMapping("/api/avatar")
public class AvatarController {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> MIME_WHITELIST =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Map<String, String> EXT = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");

    private final OssImageStorageStrategy ossStrategy;
    private final StorageConfigService storageConfigService;
    private final AgentService agentService;
    private final UserService userService;
    private final AuditService auditService;
    private final TeapotAiProperties properties;

    public AvatarController(OssImageStorageStrategy ossStrategy,
                            StorageConfigService storageConfigService,
                            AgentService agentService,
                            UserService userService,
                            AuditService auditService,
                            TeapotAiProperties properties) {
        this.ossStrategy = ossStrategy;
        this.storageConfigService = storageConfigService;
        this.agentService = agentService;
        this.userService = userService;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** Agent 头像上传：multipart file → OSS → t_agent.avatar */
    @PostMapping("/agent/{agentKey}")
    public Result<Map<String, String>> uploadAgentAvatar(@PathVariable("agentKey") String agentKey,
                                                         @RequestParam("file") MultipartFile file) {
        UploadedAvatar up = readAndValidate(file);
        StoredImage stored = ossStrategy.storeAvatar(up.data(), up.mediaType(), avatarRecord(),
                objectKey("agent/" + safeId(agentKey), up.mediaType()));
        agentService.updateAvatar(agentKey, stored.url());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", stored.url());
        return Result.ok(body);
    }

    /** 当前登录用户头像上传：multipart file → OSS → t_user.avatar（仅本人） */
    @PostMapping("/user")
    public Result<Map<String, String>> uploadUserAvatar(@RequestParam("file") MultipartFile file) {
        UploadedAvatar up = readAndValidate(file);
        String userId = ContextUtil.currentUserId();
        StoredImage stored = ossStrategy.storeAvatar(up.data(), up.mediaType(), avatarRecord(),
                objectKey("user/" + safeId(userId), up.mediaType()));
        userService.updateAvatar(stored.url());
        auditService.log("user.avatar", userId, null);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", stored.url());
        return Result.ok(body);
    }

    /** 头像承载记录（SPEC §23）：yml 指定记录名，需存在且凭证齐备、OSS 总开关开启 */
    private StorageConfigDO avatarRecord() {
        if (!properties.getStorage().getOss().isEnabled()) {
            throw new BizException("OSS 总开关已关闭，无法上传头像，请联系管理员");
        }
        String recordName = properties.getStorage().getAvatarRecord();
        StorageConfigDO record = storageConfigService.getPlain(recordName == null ? "" : recordName.trim());
        if (record == null) {
            throw new BizException("头像存储记录不存在：" + recordName + "，请在系统配置 - 存储中新建");
        }
        if (isBlank(record.getAccessKeyId()) || isBlank(record.getAccessKeySecret())
                || isBlank(record.getRegion()) || isBlank(record.getBucket())) {
            throw new BizException("头像存储记录 " + record.getName() + " 凭证不齐（需 AK/Secret/Region/Bucket）");
        }
        return record;
    }

    /** key = {avatarKeyPrefix}{type}/{id}-{ts}.{ext}；时间戳避免换头像后命中旧缓存 */
    private String objectKey(String suffixWithoutExt, String mediaType) {
        String prefix = properties.getStorage().getAvatarKeyPrefix();
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix + suffixWithoutExt + "-" + System.currentTimeMillis()
                + "." + EXT.getOrDefault(mediaType, "bin");
    }

    private record UploadedAvatar(byte[] data, String mediaType) {
    }

    private UploadedAvatar readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("头像超过 2MB 限制");
        }
        String mediaType = mediaType(file);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }
        if (!magicMatches(data, mediaType)) {
            throw new BizException("文件内容与声明的图片类型不符");
        }
        return new UploadedAvatar(data, mediaType);
    }

    private static String mediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new BizException("不支持的图片类型，仅支持 JPEG/PNG/WebP/GIF");
        }
        String mt = contentType.toLowerCase().split(";")[0].trim();
        if (!MIME_WHITELIST.contains(mt)) {
            throw new BizException("不支持的图片类型，仅支持 JPEG/PNG/WebP/GIF");
        }
        return mt;
    }

    /** magic number 校验：JPEG / PNG / WEBP / GIF（与对话图片上传一致） */
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

    /** 对象 key 中的 id 仅保留安全字符 */
    private static String safeId(String raw) {
        return raw == null ? "unknown" : raw.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
