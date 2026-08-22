package cn.shangjingu.platform.iam.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MfaSecretCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final String masterKeyBase64;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(@Value("${sjg.security.mfa.master-key-base64:}") String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64 == null ? "" : masterKeyBase64.trim();
    }

    byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new MfaRejectedException("MFA secret encryption failed", ex);
        }
    }

    byte[] decrypt(byte[] encoded) {
        if (encoded == null || encoded.length <= IV_BYTES) throw new MfaRejectedException("invalid MFA ciphertext");
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new MfaRejectedException("MFA secret decryption failed", ex);
        }
    }

    private SecretKeySpec key() {
        if (masterKeyBase64.isBlank()) throw new MfaRejectedException("MFA master key is not configured");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(masterKeyBase64);
        } catch (IllegalArgumentException ex) {
            throw new MfaRejectedException("MFA master key is invalid", ex);
        }
        if (bytes.length != 32) throw new MfaRejectedException("MFA master key must decode to 32 bytes");
        return new SecretKeySpec(bytes, "AES");
    }
}
