package cn.shangjingu.platform.iam.mfa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpCredentialServiceTest {
    @Test
    void rfcStyleTotpRoundTripAcceptsCurrentWindowAndRejectsWrongCode() {
        byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Instant at = Instant.ofEpochSecond(59);
        String code = TotpCredentialService.code(secret, at.getEpochSecond() / 30);
        assertTrue(TotpCredentialService.verify(secret, code, at));
        assertFalse(TotpCredentialService.verify(secret, "000000", at));
        assertEquals(6, code.length());
    }

    @Test
    void base32NeverLeaksBinaryFormatting() {
        String value = TotpCredentialService.base32(new byte[] {1, 2, 3, 4, 5});
        assertTrue(value.matches("[A-Z2-7]+"));
    }
}
