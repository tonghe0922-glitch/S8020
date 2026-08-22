package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuardedLeaveRepositoryReferenceTest {
    private static final UUID EMPLOYEE_ID = UUID.fromString("41000000-0000-0000-0000-000000000010");

    @Test
    void compactsHandoverEmployeeUuidForCanonicalVarchar32() {
        String canonical = GuardedLeaveRepository.canonicalHandoverAgentId(EMPLOYEE_ID.toString());

        assertEquals(32, canonical.length());
        assertEquals(EMPLOYEE_ID.toString().replace("-", ""), canonical);
    }

    @Test
    void rejectsHandoverReferenceThatCannotFitCanonicalColumn() {
        assertThrows(
                ProcessRejectedException.class, () -> GuardedLeaveRepository.canonicalHandoverAgentId("X".repeat(33)));
    }
}
