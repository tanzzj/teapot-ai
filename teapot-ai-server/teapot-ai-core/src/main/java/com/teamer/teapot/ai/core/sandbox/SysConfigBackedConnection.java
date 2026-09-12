package com.teamer.teapot.ai.core.sandbox;

import com.teamer.teapot.ai.core.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局接入配置的共用解析（SPEC §16.5）：env 存在时优先于 DB（t_sys_config），
 * 供 AgentRun / E2B 两条链路复用。解密失败（主密钥变更等）按未配置处理，
 * 只告警不抛出，避免拖垮 Agent 构建。
 */
@Slf4j
public abstract class SysConfigBackedConnection {

    protected final SysConfigService sysConfigService;

    protected SysConfigBackedConnection(SysConfigService sysConfigService) {
        this.sysConfigService = sysConfigService;
    }

    protected String resolve(String envValue, String dbKey) {
        if (notBlank(envValue)) {
            return envValue.trim();
        }
        try {
            String dbValue = sysConfigService.getPlain(dbKey);
            return notBlank(dbValue) ? dbValue.trim() : null;
        } catch (Exception e) {
            log.warn("读取系统配置失败 key={}", dbKey, e);
            return null;
        }
    }

    protected static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
