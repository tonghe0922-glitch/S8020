package cn.shangjingu.platform.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileObjectServiceTest {
    @Test
    void sha256AndScanStateMachineAreDeterministic() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                FileObjectService.sha256("hello".getBytes(StandardCharsets.UTF_8)));
        assertTrue(FileObjectService.validTransition(FileObjectService.ScanStatus.PENDING, FileObjectService.ScanStatus.SCANNING));
        assertTrue(FileObjectService.validTransition(FileObjectService.ScanStatus.SCANNING, FileObjectService.ScanStatus.SAFE));
        assertTrue(FileObjectService.validTransition(FileObjectService.ScanStatus.FAILED, FileObjectService.ScanStatus.SCANNING));
        assertFalse(FileObjectService.validTransition(FileObjectService.ScanStatus.PENDING, FileObjectService.ScanStatus.SAFE));
        assertFalse(FileObjectService.validTransition(FileObjectService.ScanStatus.INFECTED, FileObjectService.ScanStatus.SAFE));
        assertFalse(FileObjectService.validTransition(FileObjectService.ScanStatus.SAFE, FileObjectService.ScanStatus.SCANNING));
    }
}
