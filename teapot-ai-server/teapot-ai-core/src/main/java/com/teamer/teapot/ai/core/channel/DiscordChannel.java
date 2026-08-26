package com.teamer.teapot.ai.core.channel;

import com.neovisionaries.ws.client.WebSocketFactory;
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
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Discord channel 适配器（SPEC §24 修订：SDK 无官方 Java Discord 扩展，自实现 Channel 接口）。
 * 对齐 Python 版语义：Gateway WebSocket 长连（JDA）收发消息，无需公网回调；
 * - guild 频道默认 only_at_reply（仅 @机器人 时响应，DM 不受限）；
 * - 回复按 Markdown 直发，单条 2000 字符上限自动分段；
 * - bot 自身消息与 webhook 消息一律忽略（防环）。
 * 凭证：botToken（存 t_channel_config.app_secret 列，AES-GCM）。
 * 身份（§24.2 修订）：channel 链路 userId 固定取渠道名称（senderId=channelType），
 * 使沙箱状态分槽与会话索引拿到稳定可辨识的身份；出站回复目标仍由 peer.id 决定，不受影响。
 */
@Slf4j
public final class DiscordChannel extends ListenerAdapter implements Channel {

    /** Discord 单条消息长度上限 */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** 出站发送失败（代理线路抖动/SSL 握手被断等）最大重试次数 */
    private static final int MAX_SEND_RETRIES = 3;

    /** 重试基准退避（毫秒），实际 delay = base * 2^attempt（1.5s/3s/6s） */
    private static final long RETRY_BASE_DELAY_MS = 1500L;

    private final String channelId;
    private final ChannelConfig config;
    private final String botToken;
    private final boolean onlyAtReply;
    /** channel 链路统一身份：渠道名称（如 discord），作为 InboundMessage.senderId → RuntimeContext.userId */
    private final String channelIdentity;
    private final ChannelRouter router;
    /** 入站侧登记的目标 → MessageChannel 缓存（guild 频道 id / DM 用户 id） */
    private final Map<String, MessageChannel> knownChannels = new ConcurrentHashMap<>();

    /** 出站发送失败重试调度器（守护线程，不阻塞应用关闭） */
    private final ScheduledExecutorService sendRetryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discord-send-retry");
        t.setDaemon(true);
        return t;
    });

    private volatile Gateway gateway;
    private volatile JDA jda;

    public DiscordChannel(String channelId, ChannelConfig config, String botToken, boolean onlyAtReply,
                          String channelIdentity) {
        this.channelId = channelId;
        this.config = config;
        this.botToken = botToken;
        this.onlyAtReply = onlyAtReply;
        this.channelIdentity = channelIdentity;
        this.router = new ChannelRouter(channelId);
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

    /**
     * 建立 Discord Gateway 长连；不阻塞应用启动，就绪事件异步打日志。
     * 注：JDA 底层 nv-websocket-client 不走 JVM -Dhttps.proxyHost（仅 HTTP 客户端生效），
     * Gateway 长连需显式配 HTTP CONNECT 代理：discord.proxy.host/port（服务器 clash 7890，§24.10）。
     */
    @Override
    public void start() {
        JDABuilder builder = JDABuilder.createLight(botToken,
                        GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES)
                .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                .addEventListeners(this);
        String proxyHost = System.getProperty("discord.proxy.host");
        String proxyPort = System.getProperty("discord.proxy.port", "7890");
        if (proxyHost != null && !proxyHost.isBlank()) {
            try {
                WebSocketFactory wsFactory = new WebSocketFactory();
                wsFactory.getProxySettings().setHost(proxyHost.trim());
                wsFactory.getProxySettings().setPort(Integer.parseInt(proxyPort.trim()));
                builder.setWebsocketFactory(wsFactory);
                log.info("Discord Gateway 启用 HTTP 代理 {}:{}", proxyHost, proxyPort);
            } catch (NumberFormatException e) {
                log.warn("discord.proxy.port 非法：{}，将直连 Gateway（服务器可能不可达）", proxyPort);
            }
        }
        jda = builder.build();
        log.info("Discord channel 已发起连接 channelId={}", channelId);
    }

    @Override
    public void stop() {
        sendRetryExecutor.shutdownNow();
        JDA client = jda;
        if (client != null) {
            client.shutdown();
            jda = null;
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        log.info("Discord channel 已就绪 channelId={} bot={}", channelId, event.getJDA().getSelfUser().getName());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        User author = event.getAuthor();
        // bot 自身/webhook 消息忽略（防环，对齐 common 包 BotLoopGuard 语义）
        if (author.isBot() || event.isWebhookMessage()) {
            return;
        }
        JDA client = jda;
        if (client == null) {
            return;
        }
        Message message = event.getMessage();
        if (event.isFromGuild()) {
            boolean mentioned = message.getMentions().isMentioned(client.getSelfUser());
            if (onlyAtReply && !mentioned) {
                // 诊断日志：guild 消息未被 @ 本机器人（手打名字不等于真实 mention 实体，改名后易踩），静默忽略
                log.info("Discord 消息未提及机器人，忽略 channelId={} author={} text={}",
                        channelId, author.getName(), message.getContentDisplay());
                return;
            }
            String text = stripSelfMention(message, client);
            if (text.isBlank()) {
                return;
            }
            // guild 频道：peer=频道 id（出站回复目标），guild=服务器 id；senderId=渠道名称（§24.2 修订，沙箱/会话索引身份）
            knownChannels.put(event.getChannel().getId(), event.getChannel());
            InboundMessage inbound = InboundMessage
                    .builder(channelId, Peer.channel(event.getChannel().getId()), List.of(toMsg(text, author)))
                    .guild(event.getGuild().getId())
                    .senderId(channelIdentity)
                    .build();
            dispatch(inbound).subscribe(m -> {
            }, err -> log.warn("Discord 入站处理失败 channelId={} guild={}", channelId, event.getGuild().getId(), err));
        } else {
            // DM：始终响应；peer=用户 id（回复经 knownChannels/openPrivateChannel 定位），senderId=渠道名称
            String text = message.getContentDisplay();
            if (text.isBlank()) {
                return;
            }
            knownChannels.put(author.getId(), event.getChannel());
            dispatch(InboundMessage
                            .builder(channelId, Peer.direct(author.getId()), List.of(toMsg(text, author)))
                            .senderId(channelIdentity)
                            .build())
                    .subscribe(m -> {
                    }, err -> log.warn("Discord DM 处理失败 channelId={} user={}", channelId, author.getId(), err));
        }
    }

    /**
     * 路由后交给 gateway 执行（模式同官方 Custom Channel 文档）。
     * 注：HarnessGateway.run() 不自动投递回复，仅将最终回复经 Mono 返回（字节码已验证），
     * channel 必须自行将结果 deliver 到 outboundAddress，否则用户永远收不到回复。
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

    /** 出站投递：目标为 guild 频道 id 或 DM 用户 id；文本按 2000 字符分段发送 */
    @Override
    public void deliver(OutboundAddress address, List<Msg> msgs) {
        log.info("Discord 出站投递 channelId={} to={} msgs={}", channelId, address == null ? null : address.to(), msgs.size());
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
        String to = extractTargetId(address.to());
        if (to == null) {
            log.warn("Discord 出站地址无法解析 channelId={} rawTo={}", channelId, address.to());
            return;
        }
        for (String chunk : split(buffer.toString())) {
            sendText(to, chunk);
        }
    }

    /**
     * 从 OutboundAddress.to 提取真实 Discord snowflake。
     * SDK 格式为 "{channelId} {PeerKind.value}:{peerId}"（如 "discord-DC Bot :CHANNEL:123"），
     * 真实 id 在最后一个冒号后；兼容纯 snowflake 直传场景。解析失败返回 null（避免 JDA 抛 NumberFormatException）。
     */
    private static String extractTargetId(String rawTo) {
        if (rawTo == null || rawTo.isBlank()) {
            return null;
        }
        int cut = rawTo.lastIndexOf(':');
        String candidate = (cut >= 0 && cut < rawTo.length() - 1) ? rawTo.substring(cut + 1).trim() : rawTo.trim();
        return candidate.chars().allMatch(Character::isDigit) && !candidate.isEmpty() ? candidate : null;
    }

    /** 定位目标频道并发送；未缓存时回退 JDA 查询（频道 id 直查 / 用户 id 开私聊）；
     *  发送失败（代理线路抖动常见 SSLHandshakeException）指数退避重试，避免回复静默丢失 */
    private void sendText(String to, String text) {
        sendTextAttempt(to, text, 0);
    }

    private void sendTextAttempt(String to, String text, int attempt) {
        JDA client = jda;
        if (client == null || to == null) {
            return;
        }
        Consumer<Throwable> onFailure = err -> handleSendFailure(to, text, attempt, err);
        MessageChannel cached = knownChannels.get(to);
        if (cached != null) {
            cached.sendMessage(text).queue(ok -> {
            }, onFailure);
            return;
        }
        TextChannel textChannel = client.getTextChannelById(to);
        if (textChannel != null) {
            knownChannels.put(to, textChannel);
            textChannel.sendMessage(text).queue(ok -> {
            }, onFailure);
            return;
        }
        User user = client.getUserById(to);
        if (user != null) {
            user.openPrivateChannel().queue(pc -> {
                knownChannels.put(to, pc);
                pc.sendMessage(text).queue(ok -> {
                }, onFailure);
            }, onFailure);
            return;
        }
        log.warn("Discord 出站目标未找到 to={} channelId={}", to, channelId);
    }

    /** 发送失败退避重试；重试耗尽后记 ERROR 级别日志（回复丢失唯一可见线索） */
    private void handleSendFailure(String to, String text, int attempt, Throwable err) {
        if (attempt < MAX_SEND_RETRIES) {
            long delayMs = RETRY_BASE_DELAY_MS * (1L << attempt);
            log.warn("Discord 发送失败，将重试 to={} attempt={}/{} delayMs={} err={}",
                    to, attempt + 1, MAX_SEND_RETRIES, delayMs, err.toString());
            sendRetryExecutor.schedule(() -> sendTextAttempt(to, text, attempt + 1), delayMs, TimeUnit.MILLISECONDS);
        } else {
            log.error("Discord 发送失败，重试耗尽放弃投递 to={} channelId={}", to, channelId, err);
        }
    }

    private static Msg toMsg(String text, User author) {
        return Msg.builder()
                .textContent(text)
                .role(MsgRole.USER)
                .name(author.getName())
                .build();
    }

    /** 去掉对本机器人的 @ 提及（<@id> 与 <@!id> 两种形态），保留其余正文 */
    private static String stripSelfMention(Message message, JDA client) {
        String selfId = client.getSelfUser().getId();
        return message.getContentDisplay()
                .replace("<@" + selfId + ">", "")
                .replace("<@!" + selfId + ">", "")
                .trim();
    }

    /** 按 Discord 2000 字符上限分段（优先在换行处切，避免截断单词之外的硬切过多） */
    private static List<String> split(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String rest = text;
        while (rest.length() > MAX_MESSAGE_LENGTH) {
            int cut = rest.lastIndexOf('\n', MAX_MESSAGE_LENGTH);
            if (cut <= MAX_MESSAGE_LENGTH / 2) {
                cut = MAX_MESSAGE_LENGTH;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        if (!rest.isBlank()) {
            out.add(rest);
        }
        return out;
    }
}
