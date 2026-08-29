package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Channel 连接器记录（表 t_channel_config，SPEC §24.3）。
 * channelType=dingtalk 消费 appKey/appSecret/robotCode；discord 消费 appSecret（botToken）；
 * github 消费 appSecret（PAT token）/webhookSecret，app_key 列复用为可选 bot login；
 * DB 中 app_secret/webhook_secret 为 AES-GCM 密文，Service 层解密后回填明文供 ChannelHub 消费。
 */
@Data
public class ChannelConfigDO implements Serializable {

    private Long id;
    /** 记录名（唯一标识，Agent feature.channel.channelRecord 引用） */
    private String name;
    /** dingtalk / discord / github */
    private String channelType;
    /** 钉钉应用 ClientID（明文）；github 复用为可选 bot 账号 login */
    private String appKey;
    /** 钉钉应用 ClientSecret / Discord Bot Token / GitHub PAT（DB 为密文） */
    private String appSecret;
    /** 机器人 robotCode，缺省同 appKey */
    private String robotCode;
    /** GitHub webhook secret（DB 为密文，校验 X-Hub-Signature-256） */
    private String webhookSecret;
    private String remark;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
