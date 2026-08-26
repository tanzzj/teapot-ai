package com.teamer.teapot.ai.log;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * 日志消息脱敏转换器（SPEC §15.12 补充）：剥离日志消息中 URL 的 userinfo 凭据。
 *
 * <p>背景：官方 {@code GitSkillRepository} 在 clone/pull 的 INFO 日志中原样打印
 * remote URL，若 remote 携带 {@code user:pass@} 形式的凭据会明文落入
 * journald。平台自身的状态/审计输出已脱敏，此处对输出层统一兜底。
 */
public class MaskingMessageConverter extends MessageConverter {

    /** 匹配 scheme://user:pass@（user 不含 :/@，密码段不含空白） */
    private static final Pattern USERINFO = Pattern.compile("(\\w+://)([^:/@\\s]+):([^@\\s]+)@");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = super.convert(event);
        return msg == null ? null : USERINFO.matcher(msg).replaceAll("$1$2:****@");
    }
}
