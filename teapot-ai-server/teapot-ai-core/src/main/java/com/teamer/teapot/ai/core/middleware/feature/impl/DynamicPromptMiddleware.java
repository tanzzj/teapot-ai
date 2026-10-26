package com.teamer.teapot.ai.core.middleware.feature.impl;

import com.teamer.teapot.ai.core.middleware.feature.ConfigField;
import com.teamer.teapot.ai.core.middleware.feature.FeatureDrivenMiddleware;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 动态系统提示词（SPEC §29.23）：在 Agent 自己的 sysPrompt 之外，按 Agent 追加一段每次装配都重算的上下文。
 *
 * <p>主要用途是让模型知道"现在是什么时候"——不注入时它按训练截止日回答日期/星期/时效性问题；
 * 顺带给一句固定补充说明（不改 sysPrompt 列，也就不会污染用户自己写的那份提示词）。
 */
@Slf4j
@Component
public class DynamicPromptMiddleware extends FeatureDrivenMiddleware<DynamicPromptMiddleware.Config> {

    /** 格式或时区写错时回落的这一份，保证"配置手滑"不至于让整轮对话起不来 */
    private static final DateTimeFormatter FALLBACK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Shanghai");

    @Data
    public static class Config {

        @ConfigField(
                label = "注入当前时间",
                description = "告诉模型今天是哪天、星期几，避免它按训练截止日回答日期与时效性问题",
                defaultValue = "true")
        private Boolean withDate;

        @ConfigField(
                label = "时间格式",
                description = "java.time 的 pattern，中文星期用 EEEE",
                defaultValue = "yyyy-MM-dd HH:mm EEEE")
        private String datePattern;

        @ConfigField(
                label = "时区",
                description = "ZoneId 名称，例如 Asia/Shanghai、UTC",
                defaultValue = "Asia/Shanghai")
        private String timeZone;

        @ConfigField(
                label = "追加说明",
                description = "固定拼到系统提示词末尾的一段话；留空 = 不追加")
        private String appendText;
    }

    @Override
    public String description() {
        return "按 Agent 追加动态系统提示词：注入当前时间（可选格式与时区）与一段固定补充说明";
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    @Override
    protected Mono<String> onSystemPrompt(Config config, Agent agent, RuntimeContext ctx, String currentPrompt) {
        StringBuilder prompt = new StringBuilder(currentPrompt == null ? "" : currentPrompt);
        if (!Boolean.FALSE.equals(config.getWithDate())) {
            prompt.append("\n\n## 当前时间\n").append(nowText(config));
        }
        String append = config.getAppendText();
        if (append != null && !append.isBlank()) {
            prompt.append("\n\n## 补充说明\n").append(append.trim());
        }
        return Mono.just(prompt.toString());
    }

    private String nowText(Config config) {
        return ZonedDateTime.now(zone(config)).format(format(config));
    }

    private ZoneId zone(Config config) {
        String zoneId = config.getTimeZone();
        if (zoneId == null || zoneId.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (RuntimeException e) {
            log.warn("动态提示词时区非法，回落 {} timeZone={} 原因={}", FALLBACK_ZONE, zoneId, e.getMessage());
            return FALLBACK_ZONE;
        }
    }

    private DateTimeFormatter format(Config config) {
        String pattern = config.getDatePattern();
        if (pattern == null || pattern.isBlank()) {
            return FALLBACK_FORMAT;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern.trim(), Locale.CHINA);
        } catch (RuntimeException e) {
            log.warn("动态提示词时间格式非法，回落 {} pattern={} 原因={}", FALLBACK_FORMAT, pattern, e.getMessage());
            return FALLBACK_FORMAT;
        }
    }
}
