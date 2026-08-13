package com.teamer.teapot.ai.core.config;

import com.teamer.teapot.ai.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证对称加密（SPEC §16.5.1）：AES-256-GCM 认证加密（防篡改，禁用无认证模式）。
 * 每次加密随机 12B IV，密文格式 {@code v{keyVersion}:{base64(iv | ciphertext | tag)}}。
 * 主密钥 TEAPOT_SECRET_KEY（32B base64）仅 app.env 环境变量，绝不入库不入 git。
 */
@Slf4j
@Component
public class ConfigCryptoService {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    /** 当前主密钥版本（轮换留二期：v2 密钥 + 重加密工具） */
    private static final int KEY_VERSION = 1;

    private final String secretKeyBase64;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec masterKey;

    public ConfigCryptoService(@Value("${TEAPOT_SECRET_KEY:}") String secretKeyBase64) {
        this.secretKeyBase64 = secretKeyBase64;
    }

    @PostConstruct
    void init() {
        if (secretKeyBase64 == null || secretKeyBase64.isBlank()) {
            log.warn("TEAPOT_SECRET_KEY 未配置：凭证加密能力不可用（加密写入将被拒绝）");
            return;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secretKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TEAPOT_SECRET_KEY 不是合法 base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("TEAPOT_SECRET_KEY 必须为 32 字节（AES-256）base64 编码");
        }
        this.masterKey = new SecretKeySpec(key, "AES");
    }

    /** 主密钥是否就绪 */
    public boolean available() {
        return masterKey != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v" + KEY_VERSION + ":" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.error("凭证加密失败", e);
            throw new BizException("凭证加密失败，请检查主密钥配置");
        }
    }

    public String decrypt(String ciphertext) {
        requireKey();
        if (ciphertext == null || !ciphertext.startsWith("v" + KEY_VERSION + ":")) {
            throw new BizException("密文格式或密钥版本不匹配");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext.substring(("v" + KEY_VERSION + ":").length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(raw, GCM_IV_BYTES, raw.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM tag 校验失败 = 密文被篡改或主密钥错误，统一文案不泄露细节
            log.error("凭证解密失败（密文篡改或主密钥不匹配）");
            throw new BizException("凭证解密失败，请检查主密钥配置");
        }
    }

    /** 脱敏掩码：只显末 4 位（SPEC §16.5.1 GET 回显规则） */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private void requireKey() {
        if (masterKey == null) {
            throw new BizException("凭证加解密不可用：服务器未配置 TEAPOT_SECRET_KEY");
        }
    }
}
