package com.teamer.teapot.ai.core.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub channel 适配器（SPEC §24 修订：官方扩展随 2.2.0-RC2 发布、锁 2.0.1 不可用，
 * 参考 agentscope-java 仓库 agentscope-extensions-channel-github 源码自实现）。
 * 与钉钉（Stream 出站长连）/Discord（Gateway 出站长连）不同，GitHub 走公网 webhook 回调：
 * - 入站：GitHub 将 issue_comment / pull_request_review_comment 事件 POST 到
 *   {@code /api/webhook/github/{记录名}}（{@link GitHubWebhookController}），
 *   经 HMAC-SHA256 签名校验（X-Hub-Signature-256）+ 幂等去重（comment.id）+ 防环（bot 自身评论）后 dispatch；
 * - 出站：以 PAT 身份在原 issue/PR 线程追加评论（POST /repos/{owner}/{repo}/issues/{n}/comments，
 *   GitHub 对 PR 会话评论同样走 issue comments 端点）；
 * - peer 模型：PeerKind.THREAD，peerId = "owner/repo#number"（出站客户端据此还原评论目标）。
 * 凭证：PAT token（t_channel_config.app_secret，AES-GCM）+ webhook secret（webhook_secret，AES-GCM）；
 * 身份（§24.2 修订）：senderId 固定取渠道名称（github）。
 */
@Slf4j
public final class GitHubChannel implements Channel {

    /** 进程级注册表：记录名 → channel 实例（webhook 按记录名路由，同官方 GitHubChannelRegistry 语义） */
    private static final Map<String, GitHubChannel> REGISTRY = new ConcurrentHashMap<>();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 幂等去重容量（comment.id 不可变，GitHub 重试投递同 id） */
    private static final int IDEMPOTENCY_CAPACITY = 512;

    private final String channelId;
    private final ChannelConfig config;
    private final String token;
    private final byte[] webhookSecret;
    private final String apiBase;
    /** bot 账号 login（可选）：防环比对；缺省时回退评论者 user.type=="Bot" 判断 */
    private final String botLogin;
    /** channel 链路统一身份：渠道名称（如 github），作为 InboundMessage.senderId → RuntimeContext.userId */
    private final String channelIdentity;
    private final ChannelRouter router;

    /** 已处理 comment.id（LRU 有界，防 webhook 重试重复触发） */
    private final Map<Long, Boolean> seenCommentIds = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > IDEMPOTENCY_CAPACITY;
                }
            });

    private volatile Gateway gateway;

    public GitHubChannel(String channelId, ChannelConfig config, String token, String webhookSecret,
                         String apiBase, String botLogin, String channelIdentity) {
        this.channelId = channelId;
        this.config = config;
        this.token = token;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://api.github.com" : apiBase.trim();
        this.botLogin = botLogin == null ? null : botLogin.trim();
        this.channelIdentity = channelIdentity;
        this.router = new ChannelRouter(channelId);
    }

    /** webhook 控制器按记录名定位 channel 实例 */
    public static GitHubChannel get(String recordName) {
        return REGISTRY.get(recordName);
    }

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void start() {
        // registry key 用记录名（channelId 去掉 "github-" 前缀），与 webhook 路径 /{记录名} 对应
        REGISTRY.put(recordName(), this);
        log.info("GitHub channel 已启动 channelId={} apiBase={} botLogin={}",
                channelId, apiBase, botLogin == null ? "(未配置，按 user.type 防环)" : botLogin);
    }

    @Override
    public void stop() {
        REGISTRY.remove(recordName());
        log.info("GitHub channel 已停止 channelId={}", channelId);
    }

    private String recordName() {
        return channelId.startsWith("github-") ? channelId.substring("github-".length()) : channelId;
    }

    // -----------------------------------------------------------------
    //  入站：webhook 处理（控制器转交原始报文）
    // -----------------------------------------------------------------

    /**
     * 处理一次 webhook 投递：签名校验 → 事件过滤 → 幂等去重 → 防环 → 映射入站消息 → dispatch。
     * 返回 HTTP 状态码（非 202 的均为快速拒绝；202 = 已受理异步执行）。
     */
    public int handleWebhook(String eventType, String signature, byte[] rawBody) {
        if (!verifySignature(signature, rawBody)) {
            log.warn("GitHub webhook 签名校验失败 channelId={}", channelId);
            return 401;
        }
        // 只处理评论类事件，其余事件 204 让 GitHub 停止重试
        if (eventType == null
                || !(eventType.equals("issue_comment") || eventType.equals("pull_request_review_comment"))) {
            return 204;
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(rawBody);
        } catch (Exception e) {
            log.warn("GitHub webhook JSON 解析失败 channelId={}：{}", channelId, e.getMessage());
            return 400;
        }
        // 仅响应新建评论（编辑/删除在 MVP 忽略）
        if (!"created".equals(payload.path("action").asText(null))) {
            return 200;
        }
        // 幂等：comment.id 不可变，重试投递同 id 直接丢弃
        long commentId = payload.path("comment").path("id").asLong(-1);
        if (commentId > 0 && seenCommentIds.putIfAbsent(commentId, Boolean.TRUE) != null) {
            log.debug("GitHub webhook 重复投递 comment.id={} channelId={}", commentId, channelId);
            return 200;
        }
        // 防环：bot 自身评论一律忽略（官方 GitHubChannel 同款语义）
        JsonNode commenter = payload.path("comment").path("user");
        if (isBotComment(commenter)) {
            return 200;
        }
        InboundMessage inbound = mapInbound(eventType, payload);
        if (inbound == null) {
            return 200;
        }
        dispatch(inbound).subscribe(m -> {
        }, err -> log.warn("GitHub 入站处理失败 channelId={} commentId={}", channelId, commentId, err));
        return 202;
    }

    /** 评论者是否 bot：优先按记录的 botLogin 精确比对，缺省回退 user.type=="Bot" */
    private boolean isBotComment(JsonNode commenter) {
        String login = commenter.path("login").asText(null);
        if (botLogin != null && !botLogin.isBlank()) {
            return botLogin.equalsIgnoreCase(login);
        }
        return "Bot".equalsIgnoreCase(commenter.path("type").asText(null));
    }

    /**
     * 映射入站消息（参考官方 GitHubInboundMapper）：
     * peer = THREAD "owner/repo#number"；issue_comment 取 issue.number，
     * pull_request_review_comment 取 pull_request.number（PR 会话评论出站走同一 issue comments 端点）。
     */
    private InboundMessage mapInbound(String eventType, JsonNode payload) {
        String fullName = payload.path("repository").path("full_name").asText(null);
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        long number = "issue_comment".equals(eventType)
                ? payload.path("issue").path("number").asLong(-1)
                : payload.path("pull_request").path("number").asLong(-1);
        if (number <= 0) {
            return null;
        }
        JsonNode comment = payload.path("comment");
        String body = comment.path("body").asText(null);
        String authorLogin = comment.path("user").path("login").asText(null);
        if (body == null || body.isBlank() || authorLogin == null) {
            return null;
        }
        String ownerLogin = payload.path("repository").path("owner").path("login").asText(null);
        Msg msg = Msg.builder().role(MsgRole.USER).name(authorLogin).textContent(body).build();
        return InboundMessage.builder(channelId, Peer.thread(fullName + "#" + number), List.of(msg))
                .accountId(ownerLogin)
                .senderId(channelIdentity)
                .build();
    }

    /** HMAC-SHA256 签名校验（X-Hub-Signature-256 = "sha256=" + hex(mac(secret, rawBody))，常量时间比较） */
    private boolean verifySignature(String header, byte[] rawBody) {
        if (header == null || rawBody == null || !header.startsWith("sha256=")) {
            return false;
        }
        String expectedHex;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            expectedHex = sb.toString();
        } catch (Exception e) {
            return false;
        }
        String got = header.substring("sha256=".length());
        if (expectedHex.length() != got.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < expectedHex.length(); i++) {
            r |= expectedHex.charAt(i) ^ got.charAt(i);
        }
        return r == 0;
    }

    // -----------------------------------------------------------------
    //  路由与出站
    // -----------------------------------------------------------------

    /**
     * 路由后交给 gateway 执行（模式同 DiscordChannel：run 不自动投递，需自行 deliver）。
     */
    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        RouteResult route = router.resolveRoute(config, message);
        return gateway.run(route.context(), message.messages(), route.outboundAddress())
                .doOnNext(reply -> {
                    if (reply != null) {
                        deliver(route.outboundAddress(), List.of(reply));
                    }
                });
    }

    /** 出站投递：在 peerId 指示的 issue/PR 线程追加评论（GitHub 无硬分段需求，单条直发） */
    @Override
    public void deliver(OutboundAddress address, List<Msg> msgs) {
        StringBuilder buffer = new StringBuilder();
        for (Msg msg : msgs) {
            String text = msg == null ? null : msg.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!buffer.isEmpty()) {
                buffer.append('\n');
            }
            buffer.append(text);
        }
        if (buffer.isEmpty()) {
            return;
        }
        ThreadTarget target = parseAddress(address == null ? null : address.to());
        if (target == null) {
            log.warn("GitHub 出站地址无法解析 channelId={} rawTo={}", channelId,
                    address == null ? null : address.to());
            return;
        }
        sendComment(target, buffer.toString());
    }

    /**
     * 从 OutboundAddress.to 还原 "owner/repo#number"。
     * SDK 格式为 "{channelId} {PeerKind.value}:{peerId}"（同 Discord 适配器注释），真实目标在最后一个冒号后。
     */
    private static ThreadTarget parseAddress(String rawTo) {
        if (rawTo == null || rawTo.isBlank()) {
            return null;
        }
        int cut = rawTo.lastIndexOf(':');
        String peerId = (cut >= 0 && cut < rawTo.length() - 1) ? rawTo.substring(cut + 1).trim() : rawTo.trim();
        int hash = peerId.indexOf('#');
        int slash = peerId.indexOf('/');
        if (hash < 0 || slash < 0 || slash > hash) {
            return null;
        }
        String owner = peerId.substring(0, slash);
        String repo = peerId.substring(slash + 1, hash);
        long number;
        try {
            number = Long.parseLong(peerId.substring(hash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return owner.isBlank() || repo.isBlank() || number <= 0 ? null : new ThreadTarget(owner, repo, number);
    }

    /**
     * POST /repos/{owner}/{repo}/issues/{number}/comments（issue 与 PR 会话评论共用端点）。
     * 用 HttpURLConnection + 显式 Proxy.NO_PROXY 直连：服务器境内可直达 api.github.com，
     * 而 JDK 21 的 java.net.http.HttpClient 不支持 -Dhttp.nonProxyHosts，
     * 走 clash 系统代理时出海线路不稳会导致握手失败（§24.11）。
     */
    private void sendComment(ThreadTarget target, String text) {
        try {
            String url = apiBase + "/repos/" + target.owner + "/" + target.repo
                    + "/issues/" + target.number + "/comments";
            String body = JSON.writeValueAsString(Map.of("body", text));
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL()
                    .openConnection(Proxy.NO_PROXY);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "teapot-ai");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                log.info("GitHub 回复已发送 channelId={} target={}#{}", channelId, target.owner + "/" + target.repo, target.number);
            } else {
                String respBody = "";
                try (var in = conn.getErrorStream()) {
                    if (in != null) {
                        respBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                    // 读取错误响应体失败不影响主流程
                }
                log.error("GitHub 回复发送失败 channelId={} status={} body={}",
                        channelId, status, truncate(respBody, 500));
            }
            conn.disconnect();
        } catch (Exception e) {
            log.error("GitHub 回复发送异常 channelId={}：{}", channelId, e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private record ThreadTarget(String owner, String repo, long number) {}
}
