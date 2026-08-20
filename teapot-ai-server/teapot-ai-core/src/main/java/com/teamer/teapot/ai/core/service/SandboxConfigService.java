package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.dao.SandboxConfigMapper;
import com.teamer.teapot.ai.core.model.SandboxConfigDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 沙箱连接记录管理（SPEC §22.2 多记录）：
 * 敏感列（e2b_api_key / ar_api_key / ar_account_id）AES-GCM 加密入库，读时解密；
 * Agent 通过 feature.sandbox.sandboxRecord 按名引用（无全局激活概念）。
 * 审计只记记录名不记凭证（SPEC §14.6）。
 */
@Slf4j
@Service
public class SandboxConfigService {

    private final SandboxConfigMapper sandboxConfigMapper;
    private final ConfigCryptoService cryptoService;
    private final AuditService auditService;

    public SandboxConfigService(SandboxConfigMapper sandboxConfigMapper,
                                ConfigCryptoService cryptoService,
                                AuditService auditService) {
        this.sandboxConfigMapper = sandboxConfigMapper;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    /** 全部记录（敏感列保持密文；展示脱敏由 Controller 负责） */
    public List<SandboxConfigDO> list() {
        return sandboxConfigMapper.selectAll();
    }

    /** 记录是否存在（Agent feature 保存校验用） */
    public boolean exists(String name) {
        return name != null && !name.isBlank() && sandboxConfigMapper.selectByName(name) != null;
    }

    /** 按名取记录（敏感列已解密，只读使用）；不存在返回 null */
    public SandboxConfigDO getPlain(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        SandboxConfigDO row = sandboxConfigMapper.selectByName(name);
        if (row == null) {
            return null;
        }
        decryptInPlace(row);
        return row;
    }

    /** 新建记录：name 唯一 + linkType 合法 + 对应链路凭证齐备 */
    @Transactional(rollbackFor = Exception.class)
    public void create(SandboxConfigDO record) {
        validateName(record.getName());
        if (sandboxConfigMapper.selectByName(record.getName()) != null) {
            throw new BizException("记录名已存在：" + record.getName());
        }
        normalizeLinkType(record);
        requireLinkCredentials(record);
        encryptInPlace(record);
        record.setUpdatedBy(operator());
        sandboxConfigMapper.insert(record);
        auditService.log("sandbox.record.create", record.getName(), "linkType=" + record.getLinkType());
    }

    /** 更新记录：敏感列留空不修改；linkType 变更时校验新链路凭证（含库里已有值合并判断） */
    @Transactional(rollbackFor = Exception.class)
    public void update(SandboxConfigDO record) {
        validateName(record.getName());
        SandboxConfigDO existing = sandboxConfigMapper.selectByName(record.getName());
        if (existing == null) {
            throw new BizException("记录不存在：" + record.getName());
        }
        if (notBlank(record.getLinkType())) {
            normalizeLinkType(record);
        } else {
            record.setLinkType(null);
        }
        // 合并视图校验：库内解密值 + 本次非空提交值，据此判断新 linkType 凭证是否齐备
        decryptInPlace(existing);
        SandboxConfigDO merged = new SandboxConfigDO();
        merged.setLinkType(record.getLinkType() != null ? record.getLinkType() : existing.getLinkType());
        merged.setE2bApiKey(firstNotBlank(record.getE2bApiKey(), existing.getE2bApiKey()));
        merged.setE2bApiBaseUrl(firstNotBlank(record.getE2bApiBaseUrl(), existing.getE2bApiBaseUrl()));
        merged.setE2bDomain(firstNotBlank(record.getE2bDomain(), existing.getE2bDomain()));
        merged.setApiKey(firstNotBlank(record.getApiKey(), existing.getApiKey()));
        merged.setAccountId(firstNotBlank(record.getAccountId(), existing.getAccountId()));
        merged.setRegion(firstNotBlank(record.getRegion(), existing.getRegion()));
        merged.setMcpServerUrl(firstNotBlank(record.getMcpServerUrl(), existing.getMcpServerUrl()));
        requireLinkCredentials(merged);
        // 敏感列：非空加密入库；留空置 null（动态 UPDATE 跳过，保持原值）
        record.setE2bApiKey(encryptOrNull(record.getE2bApiKey()));
        record.setApiKey(encryptOrNull(record.getApiKey()));
        record.setAccountId(encryptOrNull(record.getAccountId()));
        record.setUpdatedBy(operator());
        sandboxConfigMapper.updateByName(record);
        auditService.log("sandbox.record.update", record.getName(), null);
    }

    /** 删除记录：被 Agent 引用时不阻止（运行期降级无 shell 并 warn），仅审计留痕 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String name) {
        if (sandboxConfigMapper.deleteByName(name) == 0) {
            throw new BizException("记录不存在：" + name);
        }
        auditService.log("sandbox.record.delete", name, null);
    }

    /** 记录按其 linkType 的凭证是否齐备（解密后明文判断） */
    public static boolean linkConfigured(SandboxConfigDO plain) {
        if (plain == null || plain.getLinkType() == null) {
            return false;
        }
        return "e2b".equals(plain.getLinkType())
                ? notBlank(plain.getE2bApiKey()) && notBlank(plain.getE2bApiBaseUrl()) && notBlank(plain.getE2bDomain())
                : notBlank(plain.getApiKey()) && notBlank(plain.getAccountId())
                        && notBlank(plain.getRegion()) && notBlank(plain.getMcpServerUrl());
    }

    private static String firstNotBlank(String first, String fallback) {
        return notBlank(first) ? first : fallback;
    }

    private String encryptOrNull(String plain) {
        return notBlank(plain) ? cryptoService.encrypt(plain.trim()) : null;
    }

    private void encryptInPlace(SandboxConfigDO record) {
        record.setE2bApiKey(encryptOrNull(record.getE2bApiKey()));
        record.setApiKey(encryptOrNull(record.getApiKey()));
        record.setAccountId(encryptOrNull(record.getAccountId()));
    }

    private void decryptInPlace(SandboxConfigDO row) {
        row.setE2bApiKey(decryptQuiet(row.getE2bApiKey(), row.getName()));
        row.setApiKey(decryptQuiet(row.getApiKey(), row.getName()));
        row.setAccountId(decryptQuiet(row.getAccountId(), row.getName()));
    }

    /** 解密失败（主密钥变更等）不抛出，按空值处理并 warn（同 StorageConfigService 容错语义） */
    private String decryptQuiet(String cipher, String name) {
        if (!notBlank(cipher)) {
            return null;
        }
        try {
            return cryptoService.decrypt(cipher);
        } catch (Exception e) {
            log.warn("沙箱记录凭证解密失败 name={}", name, e);
            return null;
        }
    }

    private static String normalizeLinkType(SandboxConfigDO record) {
        String linkType = record.getLinkType() == null ? "" : record.getLinkType().trim().toLowerCase();
        if (!"e2b".equals(linkType) && !"agentrun".equals(linkType)) {
            throw new BizException("linkType 非法，可选值：e2b / agentrun");
        }
        record.setLinkType(linkType);
        return linkType;
    }

    /** 按 linkType 校验凭证齐备：e2b 三项 / agentrun 四项 */
    private static void requireLinkCredentials(SandboxConfigDO record) {
        if ("e2b".equals(record.getLinkType())) {
            if (!notBlank(record.getE2bApiKey()) || !notBlank(record.getE2bApiBaseUrl())
                    || !notBlank(record.getE2bDomain())) {
                throw new BizException("E2B 链路需填写 API Key / API Base URL / Domain");
            }
            return;
        }
        if (!notBlank(record.getApiKey()) || !notBlank(record.getAccountId())
                || !notBlank(record.getRegion()) || !notBlank(record.getMcpServerUrl())) {
            throw new BizException("AgentRun 链路需填写 API Key / 账号 ID / Region / MCP 服务地址");
        }
    }

    private static void validateName(String name) {
        if (!notBlank(name) || name.trim().length() > 64) {
            throw new BizException("记录名必填且不超过 64 字符");
        }
    }

    private static String operator() {
        return ContextUtil.currentUserId() == null ? "system" : ContextUtil.currentUserId();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
