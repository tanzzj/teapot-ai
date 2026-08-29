package com.teamer.teapot.ai.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.ChannelConfigMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.ChannelConfigDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel 连接器记录管理（SPEC §24.3/§24.4）：
 * app_secret AES-GCM 加密入库，读时解密（明文仅在 ChannelHub 构造 channel 时消费）；
 * Agent 通过 feature.channel.channelRecord 按名引用。
 * 审计只记记录名不记凭证（SPEC §14.6）。
 */
@Slf4j
@Service
public class ChannelConfigService {

    /** 渠道类型（SPEC §24.3 枚举：dingtalk v1；discord §24 修订；github §24 修订，webhook 回调适配器） */
    public static final String TYPE_DINGTALK = "dingtalk";
    public static final String TYPE_DISCORD = "discord";
    public static final String TYPE_GITHUB = "github";

    /** 测试连接专用 HTTP 客户端（走 JVM 系统代理，Discord 经 clash 出海，§24.10） */
    private static final HttpClient TEST_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final ObjectMapper TEST_JSON = new ObjectMapper();

    private final ChannelConfigMapper channelConfigMapper;
    private final AgentMapper agentMapper;
    private final ConfigCryptoService cryptoService;
    private final AuditService auditService;

    public ChannelConfigService(ChannelConfigMapper channelConfigMapper,
                                AgentMapper agentMapper,
                                ConfigCryptoService cryptoService,
                                AuditService auditService) {
        this.channelConfigMapper = channelConfigMapper;
        this.agentMapper = agentMapper;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    /** 全部记录（app_secret 保持密文；展示脱敏由 Controller 负责） */
    public List<ChannelConfigDO> list() {
        return channelConfigMapper.selectAll();
    }

    /** 按名取记录（app_secret/webhook_secret 已解密，只读使用）；不存在返回 null */
    public ChannelConfigDO getPlain(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ChannelConfigDO row = channelConfigMapper.selectByName(name);
        if (row == null) {
            return null;
        }
        row.setAppSecret(decryptQuiet(row.getAppSecret(), row.getName()));
        row.setWebhookSecret(decryptQuiet(row.getWebhookSecret(), row.getName()));
        return row;
    }

    /** 记录凭证是否齐备（解密后明文判断）：钉钉 appKey+appSecret；Discord 仅 botToken；GitHub token+webhookSecret */
    public static boolean configured(ChannelConfigDO plain) {
        if (plain == null || !notBlank(plain.getAppSecret())) {
            return false;
        }
        if (TYPE_GITHUB.equals(plain.getChannelType())) {
            return notBlank(plain.getWebhookSecret());
        }
        return TYPE_DISCORD.equals(plain.getChannelType()) || notBlank(plain.getAppKey());
    }

    /** 新建记录：name 唯一 + channelType 合法 + appKey/appSecret 齐备 */
    @Transactional(rollbackFor = Exception.class)
    public void create(ChannelConfigDO record) {
        validateName(record.getName());
        if (channelConfigMapper.selectByName(record.getName()) != null) {
            throw new BizException("记录名已存在：" + record.getName());
        }
        normalizeChannelType(record);
        requireCredentials(record.getChannelType(), record.getAppKey(), record.getAppSecret(), record.getWebhookSecret());
        record.setAppSecret(encryptOrNull(record.getAppSecret()));
        record.setWebhookSecret(encryptOrNull(record.getWebhookSecret()));
        record.setUpdatedBy(operator());
        channelConfigMapper.insert(record);
        auditService.log("channel.record.create", record.getName(), "type=" + record.getChannelType());
    }

    /** 更新记录：appSecret 留空不修改；合并库内已有值校验凭证齐备 */
    @Transactional(rollbackFor = Exception.class)
    public void update(ChannelConfigDO record) {
        validateName(record.getName());
        ChannelConfigDO existing = channelConfigMapper.selectByName(record.getName());
        if (existing == null) {
            throw new BizException("记录不存在：" + record.getName());
        }
        if (notBlank(record.getChannelType())) {
            normalizeChannelType(record);
        } else {
            record.setChannelType(null);
        }
        // 合并视图校验：库内解密值 + 本次非空提交值
        String mergedType = firstNotBlank(record.getChannelType(), existing.getChannelType());
        String mergedKey = firstNotBlank(record.getAppKey(), existing.getAppKey());
        String mergedSecret = firstNotBlank(record.getAppSecret(),
                decryptQuiet(existing.getAppSecret(), existing.getName()));
        String mergedWebhookSecret = firstNotBlank(record.getWebhookSecret(),
                decryptQuiet(existing.getWebhookSecret(), existing.getName()));
        requireCredentials(mergedType, mergedKey, mergedSecret, mergedWebhookSecret);
        record.setAppSecret(encryptOrNull(record.getAppSecret()));
        record.setWebhookSecret(encryptOrNull(record.getWebhookSecret()));
        record.setUpdatedBy(operator());
        channelConfigMapper.updateByName(record);
        auditService.log("channel.record.update", record.getName(), null);
    }

    /** 删除记录：被 Agent 引用时拒绝（SPEC §24.7 验收 5） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String name) {
        List<String> refs = referencingAgents(name);
        if (!refs.isEmpty()) {
            throw new BizException("记录被 Agent 引用，不可删除：" + refs);
        }
        if (channelConfigMapper.deleteByName(name) == 0) {
            throw new BizException("记录不存在：" + name);
        }
        auditService.log("channel.record.delete", name, null);
    }

    /** 引用该记录的 Agent 列表（含停用行，扫描全量小表） */
    public List<String> referencingAgents(String recordName) {
        List<String> refs = new ArrayList<>();
        for (AgentDO agent : agentMapper.selectPage(null, true, 0, 1000)) {
            AgentFeature.Channel ch = AgentFeature.parse(agent.getFeature()).getChannel();
            if (ch != null && recordName.equals(ch.getChannelRecord())) {
                refs.add(agent.getAgentKey());
            }
        }
        return refs;
    }

    /**
     * 测试连接（§24.10）：轻量调平台 API 验证凭证与网络，不落库不触发 channel 建连。
     * Discord：GET users/@me 验 Bot Token；钉钉：gettoken 验 AppKey/AppSecret；
     * GitHub：GET /user 验 PAT Token（服务器境内直连，不经 clash 代理）。
     */
    public Map<String, Object> testConnection(ChannelConfigDO record) {
        Map<String, Object> result = new LinkedHashMap<>();
        String type = record.getChannelType() == null ? "" : record.getChannelType().trim().toLowerCase();
        try {
            if (TYPE_DISCORD.equals(type)) {
                if (!notBlank(record.getAppSecret())) {
                    return fail(result, "请填写 Bot Token 后测试");
                }
                testDiscord(record.getAppSecret().trim(), result);
            } else if (TYPE_DINGTALK.equals(type)) {
                if (!notBlank(record.getAppKey()) || !notBlank(record.getAppSecret())) {
                    return fail(result, "请填写 AppKey 与 AppSecret 后测试");
                }
                testDingtalk(record.getAppKey().trim(), record.getAppSecret().trim(), result);
            } else if (TYPE_GITHUB.equals(type)) {
                if (!notBlank(record.getAppSecret())) {
                    return fail(result, "请填写 PAT Token 后测试");
                }
                testGithub(record.getAppSecret().trim(), result);
            } else {
                return fail(result, "不支持的渠道类型：" + type);
            }
        } catch (java.net.http.HttpTimeoutException e) {
            fail(result, "连接超时：无法到达平台 API，请检查网络/代理");
        } catch (Exception e) {
            log.warn("channel 测试连接异常 type={}", type, e);
            fail(result, "连接失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        return result;
    }

    /** Discord：Bot Token → users/@me；200 凭证有效，401 Token 无效（需经 clash 代理出海） */
    private void testDiscord(String botToken, Map<String, Object> result) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/users/@me"))
                .header("Authorization", "Bot " + botToken)
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<String> response = TEST_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode node = TEST_JSON.readTree(response.body());
            String username = node.path("username").asText("unknown");
            ok(result, "连接成功，bot：" + username);
        } else if (response.statusCode() == 401) {
            fail(result, "Bot Token 无效（401），请检查是否复制完整/已重置");
        } else {
            fail(result, "Discord API 返回异常状态：" + response.statusCode());
        }
    }

    /** 钉钉：gettoken 换 access_token；errcode=0 凭证有效（境内直连） */
    private void testDingtalk(String appKey, String appSecret, Map<String, Object> result) throws Exception {
        String url = "https://oapi.dingtalk.com/gettoken?appkey=" + URLEncoder.encode(appKey, StandardCharsets.UTF_8)
                + "&appsecret=" + URLEncoder.encode(appSecret, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<String> response = TEST_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = TEST_JSON.readTree(response.body());
        int errcode = node.path("errcode").asInt(-1);
        if (errcode == 0) {
            ok(result, "连接成功，凭证有效");
        } else {
            fail(result, "钉钉返回错误：errcode=" + errcode + "，" + node.path("errmsg").asText(""));
        }
    }

    /**
     * GitHub：PAT → GET /user；200 凭证有效，401 Token 无效。
     * 显式 Proxy.NO_PROXY 直连：服务器境内可直达 api.github.com，且 JDK 21 的 HttpClient
     * 不支持 -Dhttp.nonProxyHosts，走 clash 系统代理时出海线路不稳会握手失败（§24.11）。
     */
    private void testGithub(String token, Map<String, Object> result) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                URI.create("https://api.github.com/user").toURL()
                        .openConnection(java.net.Proxy.NO_PROXY);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "teapot-ai");
        int status = conn.getResponseCode();
        String body;
        try (var in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream()) {
            body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
        if (status == 200) {
            JsonNode node = TEST_JSON.readTree(body);
            String login = node.path("login").asText("unknown");
            ok(result, "连接成功，账号：" + login);
        } else if (status == 401) {
            fail(result, "PAT Token 无效（401），请检查是否复制完整/已过期");
        } else {
            fail(result, "GitHub API 返回异常状态：" + status);
        }
    }

    private static Map<String, Object> ok(Map<String, Object> result, String message) {
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    private static void normalizeChannelType(ChannelConfigDO record) {
        String type = record.getChannelType() == null ? "" : record.getChannelType().trim().toLowerCase();
        if (!TYPE_DINGTALK.equals(type) && !TYPE_DISCORD.equals(type) && !TYPE_GITHUB.equals(type)) {
            throw new BizException("channelType 非法，可选值：dingtalk / discord / github");
        }
        record.setChannelType(type);
    }

    /**
     * 按渠道类型校验凭证：钉钉 App Key+App Secret 双必填；Discord 仅 Bot Token（app_secret 列）必填；
     * GitHub PAT Token（app_secret 列）+ Webhook Secret 必填（app_key 列为可选 bot login）。
     */
    private static void requireCredentials(String channelType, String appKey, String appSecret, String webhookSecret) {
        if (TYPE_DISCORD.equals(channelType)) {
            if (!notBlank(appSecret)) {
                throw new BizException("Discord 连接器需填写 Bot Token");
            }
            return;
        }
        if (TYPE_GITHUB.equals(channelType)) {
            if (!notBlank(appSecret) || !notBlank(webhookSecret)) {
                throw new BizException("GitHub 连接器需填写 PAT Token 与 Webhook Secret");
            }
            return;
        }
        if (!notBlank(appKey) || !notBlank(appSecret)) {
            throw new BizException("钉钉连接器需填写 App Key（ClientID）与 App Secret（ClientSecret）");
        }
    }

    private String encryptOrNull(String plain) {
        return notBlank(plain) ? cryptoService.encrypt(plain.trim()) : null;
    }

    /** 解密失败（主密钥变更等）不抛出，按空值处理并 warn（同 SandboxConfigService 容错语义） */
    private String decryptQuiet(String cipher, String name) {
        if (!notBlank(cipher)) {
            return null;
        }
        try {
            return cryptoService.decrypt(cipher);
        } catch (Exception e) {
            log.warn("连接器记录凭证解密失败 name={}", name, e);
            return null;
        }
    }

    private static void validateName(String name) {
        if (!notBlank(name) || name.trim().length() > 64) {
            throw new BizException("记录名必填且不超过 64 字符");
        }
    }

    private static String firstNotBlank(String first, String fallback) {
        return notBlank(first) ? first : fallback;
    }

    private static String operator() {
        return ContextUtil.currentUserId() == null ? "system" : ContextUtil.currentUserId();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
