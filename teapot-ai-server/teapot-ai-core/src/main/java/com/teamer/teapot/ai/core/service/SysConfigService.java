package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.dao.SysConfigMapper;
import com.teamer.teapot.ai.core.model.SysConfigDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统配置存取（SPEC §16.5.1）：敏感项 AES-GCM 加密入库，读时解密；
 * 审计只记 key 名不记值。
 */
@Slf4j
@Service
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;
    private final ConfigCryptoService cryptoService;
    private final AuditService auditService;

    public SysConfigService(SysConfigMapper sysConfigMapper,
                            ConfigCryptoService cryptoService,
                            AuditService auditService) {
        this.sysConfigMapper = sysConfigMapper;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    /** 取明文值（密文行自动解密）；不存在返回 null */
    public String getPlain(String key) {
        SysConfigDO row = sysConfigMapper.selectByKey(key);
        if (row == null) {
            return null;
        }
        if (Integer.valueOf(1).equals(row.getEncrypted())) {
            return cryptoService.decrypt(row.getConfigValue());
        }
        return row.getConfigValue();
    }

    /** upsert；encrypt=true 时加密落库（审计只记 key 名，SPEC §14.6） */
    @Transactional(rollbackFor = Exception.class)
    public void set(String key, String value, boolean encrypt) {
        if (value == null || value.isBlank()) {
            throw new BizException("配置值不能为空：" + key);
        }
        String stored = value;
        int keyVersion = 1;
        if (encrypt) {
            stored = cryptoService.encrypt(value);
            keyVersion = 1;
        }
        String operator = ContextUtil.currentUserId() == null ? "system" : ContextUtil.currentUserId();
        sysConfigMapper.upsert(key, stored, keyVersion, encrypt ? 1 : 0, operator);
        auditService.log("config.update", key, encrypt ? "encrypted=true" : null);
    }
}
