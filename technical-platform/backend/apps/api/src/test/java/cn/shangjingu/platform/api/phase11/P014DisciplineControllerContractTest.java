package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P014DisciplineControllerContractTest {
    @Test
    void frozenPermissionsMapToFrozenServerActionsOnly() {
        assertEquals(
                P014DisciplineController.INVESTIGATE,
                P014DisciplineController.actionPermission("APPLY_SAFETY_MEASURE"));
        assertEquals(
                P014DisciplineController.INVESTIGATE,
                P014DisciplineController.actionPermission("COMPLETE_INVESTIGATION"));
        assertEquals(P014DisciplineController.APPEAL, P014DisciplineController.actionPermission("SUBMIT_DEFENSE"));
        assertEquals(
                P014DisciplineController.DECIDE,
                P014DisciplineController.actionPermission("COMPLETE_RESPONSIBILITY_REVIEW"));
        assertEquals(P014DisciplineController.DECIDE, P014DisciplineController.actionPermission("APPROVE_DECISION"));
        assertEquals(P014DisciplineController.APPEAL, P014DisciplineController.actionPermission("ACKNOWLEDGE_SERVICE"));
        assertEquals(P014DisciplineController.REMEDIATE, P014DisciplineController.actionPermission("EXECUTE_IMPACTS"));
        assertEquals(P014DisciplineController.APPEAL, P014DisciplineController.actionPermission("RESOLVE_APPEAL"));
        assertEquals(P014DisciplineController.REMEDIATE, P014DisciplineController.actionPermission("CLOSE_CORE_CASE"));
        assertEquals(
                P014DisciplineController.REMEDIATE, P014DisciplineController.actionPermission("COMPLETE_OBSERVATION"));
        assertEquals(P014DisciplineController.REMEDIATE, P014DisciplineController.actionPermission("ARCHIVE"));
        assertThrows(IllegalArgumentException.class, () -> P014DisciplineController.actionPermission("TARGET_STATUS"));
    }
}
