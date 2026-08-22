package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuardedMeetingRepositoryTest {
    private static final UUID EMPLOYEE_ID = UUID.fromString("40000000-0000-0000-0000-000000000010");

    @Test
    void compactsUuidReferenceWithoutLosingIdentity() {
        String reference = GuardedMeetingRepository.canonicalIssuerHostId(EMPLOYEE_ID.toString());

        assertEquals(32, reference.length());
        assertEquals(EMPLOYEE_ID.toString().replace("-", ""), reference);
    }

    @Test
    void preservesShortNonUuidBusinessReference() {
        assertEquals("HOST-001", GuardedMeetingRepository.canonicalIssuerHostId(" HOST-001 "));
    }

    @Test
    void rejectsReferenceThatCannotFitCanonicalColumn() {
        assertThrows(
                ProcessRejectedException.class, () -> GuardedMeetingRepository.canonicalIssuerHostId("X".repeat(33)));
    }
}
