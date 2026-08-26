package com.teamer.teapot.ai.core.channel;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.ChannelConfigDO;
import com.teamer.teapot.ai.core.service.ChannelConfigService;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;

import java.util.HashMap;
import java.util.Map;

/**
 * Channel 工厂（SPEC §24.4）：channel_type → Channel 构造；
 * 已支持 dingtalk（官方扩展）与 discord（自实现 JDA 适配器，§24 修订），后续飞书/企微只加分支。
 */
public final class ChannelFactory {

    /** dmScope 缺省（SPEC §24.3）：每个群/每个私聊各自一个会话 */
    private static final DmScope DEFAULT_DM_SCOPE = DmScope.PER_CHANNEL_PEER;

    private ChannelFactory() {
    }

    public static Channel create(ChannelConfigDO plain, String agentKey, AgentFeature.Channel feature) {
        String type = plain.getChannelType();
        if (ChannelConfigService.TYPE_DINGTALK.equals(type)) {
            return dingtalk(plain, agentKey, feature);
        }
        if (ChannelConfigService.TYPE_DISCORD.equals(type)) {
            return discord(plain, agentKey, feature);
        }
        throw new BizException("不支持的 channel 类型：" + type);
    }

    /** 钉钉 Stream channel（出站 WebSocket 反连，无需公网回调） */
    private static Channel dingtalk(ChannelConfigDO plain, String agentKey, AgentFeature.Channel feature) {
        String channelId = ChannelConfigService.TYPE_DINGTALK + "-" + plain.getName();
        DmScope dmScope = feature.getDmScope() == null || feature.getDmScope().isBlank()
                ? DEFAULT_DM_SCOPE : DmScope.valueOf(feature.getDmScope().trim());
        ChannelConfig config = ChannelConfig.builder(channelId)
                .defaultAgentId(agentKey)
                .dmScope(dmScope)
                .build();
        Map<String, Object> props = new HashMap<>();
        props.put("appKey", plain.getAppKey());
        props.put("appSecret", plain.getAppSecret());
        // robotCode 缺省同 appKey（SPEC §24.3）
        props.put("robotCode", plain.getRobotCode() == null || plain.getRobotCode().isBlank()
                ? plain.getAppKey() : plain.getRobotCode());
        return DingTalkChannel.fromProperties(channelId, config, props);
    }

    /** Discord channel（JDA Gateway WebSocket 出站长连，无需公网回调；§24 修订） */
    private static Channel discord(ChannelConfigDO plain, String agentKey, AgentFeature.Channel feature) {
        String channelId = ChannelConfigService.TYPE_DISCORD + "-" + plain.getName();
        DmScope dmScope = feature.getDmScope() == null || feature.getDmScope().isBlank()
                ? DEFAULT_DM_SCOPE : DmScope.valueOf(feature.getDmScope().trim());
        ChannelConfig config = ChannelConfig.builder(channelId)
                .defaultAgentId(agentKey)
                .dmScope(dmScope)
                .build();
        // botToken 存 app_secret 列（AES-GCM）；only_at_reply 缺省 true（对齐 py 版）；
        // 第 5 参为 channel 链路统一身份（渠道名称，§24.2 修订）
        return new DiscordChannel(channelId, config, plain.getAppSecret(), true,
                ChannelConfigService.TYPE_DISCORD);
    }
}
