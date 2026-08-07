package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Component
public class ModelSecretCipher {
    private static final String PREFIX = "enc:v1:";
    private static final byte[] AAD =
            "adaptive-learning:model-provider-secret:v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public ModelSecretCipher(@Value("${app.model-secret.encryption-key:}") String masterKey) {
        if (masterKey == null || masterKey.length() < 32) {
            this.key = null;
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] derived = digest.digest(
                    ("model-secret:v1:" + masterKey).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (Exception error) {
            throw new IllegalStateException("Could not initialize model secret encryption", error);
        }
    }

    public String protect(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.startsWith("env:") || normalized.startsWith("property:")) {
            return normalized;
        }
        requireConfigured();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception error) {
            throw unavailable("模型密钥加密失败");
        }
    }

    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            throw unavailable("模型密钥密文格式不正确");
        }
        requireConfigured();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length < 29) throw new IllegalArgumentException("encrypted payload too short");
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[payload.length - iv.length];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("模型密钥无法解密，请重新填写 API Key");
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw unavailable("未配置 MODEL_SECRET_ENCRYPTION_KEY，不能保存 API Key 明文");
        }
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, message, Map.of());
    }
}
