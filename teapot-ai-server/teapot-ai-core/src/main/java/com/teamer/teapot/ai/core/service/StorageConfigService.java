package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.dao.StorageConfigMapper;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * OSS 存储连接记录管理（SPEC §20.12 多记录）：
 * AK/Secret AES-GCM 加密入库，读时解密；激活记录名存 t_sys_config（storage.image.active）。
 * 审计只记记录名不记凭证（SPEC §14.6）。
 */
@Slf4j
@Service
public class StorageConfigService {

    private final StorageConfigMapper storageConfigMapper;
    private final SysConfigService sysConfigService;
    private final ConfigCryptoService cryptoService;
    private final AuditService auditService;
    /** 激活记录轻量缓存（上传链路每图多次取配置）；任何变更操作后清空 */
    private volatile String cachedActiveName;
    private volatile StorageConfigDO cachedActivePlain;

    public StorageConfigService(StorageConfigMapper storageConfigMapper,
                                SysConfigService sysConfigService,
                                ConfigCryptoService cryptoService,
                                AuditService auditService) {
        this.storageConfigMapper = storageConfigMapper;
        this.sysConfigService = sysConfigService;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    /** 全部记录（AK/Secret 保持密文；展示脱敏由 Controller 负责） */
    public List<StorageConfigDO> list() {
        return storageConfigMapper.selectAll();
    }

    /** 激活记录名（无则 null） */
    public String getActiveName() {
        try {
            return sysConfigService.getPlain(OssConnection.KEY_ACTIVE);
        } catch (Exception e) {
            log.warn("读取激活存储记录名失败", e);
            return null;
        }
    }

    /** 激活记录（AK/Secret 已解密，只读使用）；未设置或记录不存在返回 null */
    public StorageConfigDO getActivePlain() {
        String name = getActiveName();
        if (name == null) {
            return null;
        }
        StorageConfigDO cached = cachedActivePlain;
        if (name.equals(cachedActiveName) && cached != null) {
            return cached;
        }
        StorageConfigDO plain = getPlain(name);
        if (plain != null) {
            cachedActivePlain = plain;
            cachedActiveName = name;
        }
        return plain;
    }

    /** 按名取记录（AK/Secret 已解密）；不存在返回 null */
    public StorageConfigDO getPlain(String name) {
        StorageConfigDO row = storageConfigMapper.selectByName(name);
        if (row == null) {
            return null;
        }
        decryptInPlace(row);
        return row;
    }

    /** 新建记录：name 唯一 + AK/Secret/region/bucket 四项必填 */
    @Transactional(rollbackFor = Exception.class)
    public void create(StorageConfigDO record) {
        validateName(record.getName());
        if (storageConfigMapper.selectByName(record.getName()) != null) {
            throw new BizException("记录名已存在：" + record.getName());
        }
        requireAll(record);
        record.setAccessKeyId(cryptoService.encrypt(record.getAccessKeyId().trim()));
        record.setAccessKeySecret(cryptoService.encrypt(record.getAccessKeySecret().trim()));
        record.setUpdatedBy(operator());
        storageConfigMapper.insert(record);
        evictCache();
        auditService.log("storage.record.create", record.getName(), null);
    }

    /** 更新记录：AK/Secret 留空不修改；region/bucket 若提供须非空 */
    @Transactional(rollbackFor = Exception.class)
    public void update(StorageConfigDO record) {
        validateName(record.getName());
        StorageConfigDO existing = storageConfigMapper.selectByName(record.getName());
        if (existing == null) {
            throw new BizException("记录不存在：" + record.getName());
        }
        if (isBlank(record.getAccessKeyId())) {
            record.setAccessKeyId(null);
        } else {
            record.setAccessKeyId(cryptoService.encrypt(record.getAccessKeyId().trim()));
        }
        if (isBlank(record.getAccessKeySecret())) {
            record.setAccessKeySecret(null);
        } else {
            record.setAccessKeySecret(cryptoService.encrypt(record.getAccessKeySecret().trim()));
        }
        record.setUpdatedBy(operator());
        storageConfigMapper.updateByName(record);
        evictCache();
        auditService.log("storage.record.update", record.getName(), null);
    }

    /** 删除记录：激活中的记录禁止删除（先切换激活再删） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String name) {
        if (name != null && name.equals(getActiveName())) {
            throw new BizException("该记录为当前激活记录，请先切换激活后再删除");
        }
        if (storageConfigMapper.deleteByName(name) == 0) {
            throw new BizException("记录不存在：" + name);
        }
        evictCache();
        auditService.log("storage.record.delete", name, null);
    }

    /** 切换激活记录：记录必须存在且凭证齐备 */
    @Transactional(rollbackFor = Exception.class)
    public void setActive(String name) {
        StorageConfigDO plain = getPlain(name);
        if (plain == null) {
            throw new BizException("记录不存在：" + name);
        }
        if (isBlank(plain.getAccessKeyId()) || isBlank(plain.getAccessKeySecret())
                || isBlank(plain.getRegion()) || isBlank(plain.getBucket())) {
            throw new BizException("记录凭证不齐（需 AK/Secret/Region/Bucket），不能激活");
        }
        sysConfigService.set(OssConnection.KEY_ACTIVE, name, false);
        evictCache();
        auditService.log("storage.record.activate", name, null);
    }

    private void evictCache() {
        cachedActivePlain = null;
        cachedActiveName = null;
    }

    private void decryptInPlace(StorageConfigDO row) {
        row.setAccessKeyId(decryptQuiet(row.getAccessKeyId(), row.getName()));
        row.setAccessKeySecret(decryptQuiet(row.getAccessKeySecret(), row.getName()));
    }

    /** 解密失败（主密钥变更等）不抛出，按空值处理并 warn（同 OssConnection 容错语义） */
    private String decryptQuiet(String cipher, String name) {
        if (isBlank(cipher)) {
            return null;
        }
        try {
            return cryptoService.decrypt(cipher);
        } catch (Exception e) {
            log.warn("存储记录凭证解密失败 name={}", name, e);
            return null;
        }
    }

    private static void validateName(String name) {
        if (isBlank(name) || name.trim().length() > 64) {
            throw new BizException("记录名必填且不超过 64 字符");
        }
    }

    private static void requireAll(StorageConfigDO record) {
        if (isBlank(record.getAccessKeyId()) || isBlank(record.getAccessKeySecret())
                || isBlank(record.getRegion()) || isBlank(record.getBucket())) {
            throw new BizException("AK/Secret/Region/Bucket 四项必填");
        }
    }

    private static String operator() {
        return ContextUtil.currentUserId() == null ? "system" : ContextUtil.currentUserId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
