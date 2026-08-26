package com.teamer.teapot.ai.core.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Agent 全量会话历史列表条目（SPEC §24.9，admin 视图）：
 * Web（t_chat_session）与 channel（t_channel_session）两个索引 union 后的统一形态。
 */
@Data
@AllArgsConstructor
public class SessionHistoryItem {

    /** 会话来源：web / dingtalk / discord（后续渠道枚举扩展） */
    private String source;
    /** 用户标识：Web 平台用户 / 渠道 peer（钉钉 staffId、Discord 用户/频道 id） */
    private String userId;
    private String sessionId;
    private String title;
    /** 最近活跃时间（ISO 字符串） */
    private String lastActiveAt;
}
