package cn.shangjingu.platform.hr.profile;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProfileSensitiveValueCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final String masterKeyBase64;
    private final SecureRandom random = new SecureRandom();

    public ProfileSensitiveValueCipher(@Value("${sjg.security.profile.master-key-base64:}") String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64 == null ? "" : masterKeyBase64.trim();
    }

    public String encryptProposal(UUID tenantId, UUID requestId, String fieldCode, String plaintext) {
        return Base64.getEncoder()
                .encodeToString(encrypt(
                        plaintext.getBytes(StandardCharsets.UTF_8), aad("proposal", tenantId, requestId, fieldCode)));
    }

    public String decryptProposal(UUID tenantId, UUID requestId, String fieldCode, String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return new String(decrypt(bytes, aad("proposal", tenantId, requestId, fieldCode)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new ProcessRejectedException("P003 proposal ciphertext is invalid", ex);
        }
    }

    public byte[] encryptMaster(UUID tenantId, UUID employeeId, String fieldCode, String plaintext) {
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad("master", tenantId, employeeId, fieldCode));
    }

    private byte[] encrypt(byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new ProcessRejectedException("P003 sensitive value encryption failed", ex);
        }
    }

    private byte[] decrypt(byte[] encoded, byte[] aad) {
        if (encoded == null || encoded.length <= IV_BYTES)
            throw new ProcessRejectedException("P003 ciphertext is invalid");
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new ProcessRejectedException("P003 sensitive value decryption failed", ex);
        }
    }

    private SecretKeySpec key() {
        if (masterKeyBase64.isBlank()) throw new ProcessRejectedException("P003 profile master key is not configured");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(masterKeyBase64);
        } catch (IllegalArgumentException ex) {
            throw new ProcessRejectedException("P003 profile master key is invalid", ex);
        }
        if (key.length != 32) throw new ProcessRejectedException("P003 profile master key must decode to 32 bytes");
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] aad(String scope, UUID tenantId, UUID resourceId, String fieldCode) {
        return (scope + "|" + tenantId + "|" + resourceId + "|" + fieldCode).getBytes(StandardCharsets.UTF_8);
    }
}
