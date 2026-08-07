package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSecretCipherTest {

    @Test
    void encryptsAndDecryptsApiKeysWithRandomNonces() {
        ModelSecretCipher cipher =
                new ModelSecretCipher("test-model-secret-encryption-key-at-least-32-characters");

        String first = cipher.protect("sk-example-secret-value");
        String second = cipher.protect("sk-example-secret-value");

        assertTrue(first.startsWith("enc:v1:"));
        assertNotEquals(first, second);
        assertEquals("sk-example-secret-value", cipher.decrypt(first));
        assertEquals("sk-example-secret-value", cipher.decrypt(second));
    }

    @Test
    void preservesExternalSecretReferences() {
        ModelSecretCipher cipher =
                new ModelSecretCipher("test-model-secret-encryption-key-at-least-32-characters");

        assertEquals("env:MODEL_API_KEY", cipher.protect("env:MODEL_API_KEY"));
        assertEquals("property:app.ai.api-key", cipher.protect("property:app.ai.api-key"));
    }

    @Test
    void rejectsPlaintextStorageWithoutEncryptionKey() {
        ModelSecretCipher cipher = new ModelSecretCipher("");

        assertThrows(BusinessException.class, () -> cipher.protect("sk-example-secret-value"));
    }
}
