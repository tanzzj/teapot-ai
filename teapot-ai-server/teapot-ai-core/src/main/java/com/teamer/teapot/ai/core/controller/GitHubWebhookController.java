package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.core.channel.GitHubChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub webhook 入口（SPEC §24 修订）：{@code POST /api/webhook/github/{记录名}}。
 * 路径中的记录名即 t_channel_config.name（管理员建记录后把该 URL 填到仓库/组织 webhook 配置）。
 * 该路径在 rbac permit-list 白名单中免 JWT（GitHub 回调无平台令牌），安全边界由
 * X-Hub-Signature-256 HMAC 签名校验承担（校验失败 401）。
 * 请求体按原始 byte[] 接收，保证签名基于 GitHub 实际签名的字节序列。
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook/github")
public class GitHubWebhookController {

    @PostMapping("/{recordName}")
    public ResponseEntity<String> webhook(
            @PathVariable("recordName") String recordName,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody byte[] rawBody) {
        GitHubChannel channel = GitHubChannel.get(recordName);
        if (channel == null) {
            // 未注册：记录不存在或无 Agent 启用该记录；404 让 GitHub 侧可见投递失败
            log.warn("GitHub webhook：无已注册记录 recordName={}", recordName);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        int status;
        try {
            status = channel.handleWebhook(eventType, signature256, rawBody);
        } catch (Exception e) {
            // 内部异常不向 GitHub 泄漏细节；返回 200 避免无意义重投风暴
            log.warn("GitHub webhook 处理异常 recordName={} delivery={}：{}", recordName, deliveryId, e.getMessage(), e);
            status = 200;
        }
        return ResponseEntity.status(status).body("{}");
    }
}
