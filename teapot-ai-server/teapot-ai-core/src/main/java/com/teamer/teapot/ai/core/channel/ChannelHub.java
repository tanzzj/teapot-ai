package com.teamer.teapot.ai.core.channel;

import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.ChannelSessionMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.ChannelConfigDO;
import com.teamer.teapot.ai.core.service.AgentAssembler;
import com.teamer.teapot.ai.core.service.ChannelConfigService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.Channel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChannelHub（SPEC §24.4）：channel 链路长驻实例管理。
 * Map&lt;agentKey, GatewayBootstrap&gt;；gateway 持有 HarnessAgent 做会话排队，
 * 与 Web 链路（AgentRegistry 每轮重建）互不干扰，共享 AgentAssembler 装配规则。
 * app_secret 仅在 start 时经 ChannelConfigService.getPlain 解密消费。
 */
@Slf4j
@Component
public class ChannelHub {

    private final Map<String, GatewayBootstrap> gateways = new ConcurrentHashMap<>();

    private final AgentMapper agentMapper;
    private final AgentAssembler agentAssembler;
    private final ChannelConfigService channelConfigService;
    private final ChannelSessionMapper channelSessionMapper;

    public ChannelHub(AgentMapper agentMapper,
                      AgentAssembler agentAssembler,
                      ChannelConfigService channelConfigService,
                      ChannelSessionMapper channelSessionMapper) {
        this.agentMapper = agentMapper;
        this.agentAssembler = agentAssembler;
        this.channelConfigService = channelConfigService;
        this.channelSessionMapper = channelSessionMapper;
    }

    /** 启动扫描：全部启用 Agent 中 channel.enabled=true 者 start（单个失败仅告警不阻断，SPEC §24.7 验收 4） */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<AgentDO> enabled = agentMapper.selectAllEnabled();
        int total = 0;
        int started = 0;
        for (AgentDO agent : enabled) {
            AgentFeature.Channel ch = AgentFeature.parse(agent.getFeature()).getChannel();
            if (ch == null || !ch.isEnabled()) {
                continue;
            }
            total++;
            if (startQuietly(agent.getAgentKey())) {
                started++;
            }
        }
        log.info("Channel 启动扫描完成：启用 channel 的 Agent {} 个，成功 start {} 个", total, started);
    }

    /**
     * 同步 Agent 的 channel 状态（Agent feature / 连接器记录变更后调用）：
     * channel.enabled=true 且记录可用 → restart；否则 stop。单个失败仅告警不抛出。
     */
    public synchronized void sync(String agentKey) {
        AgentDO agent = agentMapper.selectByAgentKey(agentKey);
        AgentFeature.Channel ch = agent == null ? null : AgentFeature.parse(agent.getFeature()).getChannel();
        boolean enabled = agent != null && Integer.valueOf(1).equals(agent.getStatus())
                && ch != null && ch.isEnabled();
        if (!enabled) {
            stop(agentKey);
            return;
        }
        stop(agentKey);
        startQuietly(agentKey);
    }

    /** 启动某 Agent 的 channel：build agent（带会话索引中间件）→ 建 channel → gateway.start */
    private void start(String agentKey) {
        AgentDO agentDO = agentMapper.selectByAgentKey(agentKey);
        if (agentDO == null || !Integer.valueOf(1).equals(agentDO.getStatus())) {
            throw new IllegalStateException("Agent 不存在或已停用：" + agentKey);
        }
        AgentFeature.Channel ch = AgentFeature.parse(agentDO.getFeature()).getChannel();
        if (ch == null || !ch.isEnabled()) {
            return;
        }
        ChannelConfigDO record = channelConfigService.getPlain(ch.getChannelRecord());
        if (!ChannelConfigService.configured(record)) {
            throw new IllegalStateException("连接器记录不可用（不存在或凭证不齐）：" + ch.getChannelRecord());
        }
        HarnessAgent agent = agentAssembler.assemble(agentKey, List.of(
                new ChannelSessionIndexMiddleware(agentKey, record.getChannelType(), channelSessionMapper)));
        Channel channel = ChannelFactory.create(record, agentKey, ch);
        GatewayBootstrap gateway = GatewayBootstrap.builder()
                .agent(agentKey, agent)
                .mainAgent(agentKey)
                .channel(channel)
                .build();
        gateway.start();
        gateways.put(agentKey, gateway);
        log.info("Channel 已启动 agentKey={} record={} type={} dmScope={}",
                agentKey, record.getName(), record.getChannelType(), ch.getDmScope());
    }

    /** start 容错包装：失败仅告警（凭证错误等），不影响其他 Agent */
    private boolean startQuietly(String agentKey) {
        try {
            start(agentKey);
            return true;
        } catch (Exception e) {
            log.error("Channel 启动失败 agentKey={}：{}", agentKey, e.getMessage(), e);
            return false;
        }
    }

    /** 停止某 Agent 的 channel（未启动时静默） */
    public synchronized void stop(String agentKey) {
        GatewayBootstrap gateway = gateways.remove(agentKey);
        if (gateway == null) {
            return;
        }
        try {
            gateway.stop();
            log.info("Channel 已停止 agentKey={}", agentKey);
        } catch (Exception e) {
            log.warn("Channel 停止异常 agentKey={}", agentKey, e);
        }
    }

    /** 某 Agent 的 channel 是否运行中 */
    public boolean isRunning(String agentKey) {
        return gateways.containsKey(agentKey);
    }

    @PreDestroy
    public void shutdown() {
        for (String agentKey : List.copyOf(gateways.keySet())) {
            stop(agentKey);
        }
    }
}
