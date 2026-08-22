package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P011PerformanceControllerContractTest {
    @Test
    void scoreTypesMapOnlyToApprovedPermissions() {
        assertEquals(P011PerformanceController.SELF, P011PerformanceController.scorePermission("EMPLOYEE"));
        assertEquals(P011PerformanceController.EVALUATE, P011PerformanceController.scorePermission("SUPERVISOR"));
        assertEquals(P011PerformanceController.EVALUATE, P011PerformanceController.scorePermission("AUTHORITATIVE"));
        assertEquals(P011PerformanceController.CALIBRATE, P011PerformanceController.scorePermission("CALIBRATED"));
        assertThrows(
                IllegalArgumentException.class, () -> P011PerformanceController.scorePermission("CLIENT_SELECTED"));
    }

    @Test
    void actionsMapOnlyToApprovedPermissions() {
        assertEquals(P011PerformanceController.SELF, P011PerformanceController.actionPermission("CONFIRM_TARGETS"));
        assertEquals(P011PerformanceController.EVALUATE, P011PerformanceController.actionPermission("COLLECT_FACTS"));
        assertEquals(P011PerformanceController.CALIBRATE, P011PerformanceController.actionPermission("CALIBRATE"));
        assertEquals(P011PerformanceController.APPEAL, P011PerformanceController.actionPermission("RESOLVE_APPEAL"));
        assertEquals(P011PerformanceController.IMPACT, P011PerformanceController.actionPermission("ARCHIVE"));
        assertThrows(IllegalArgumentException.class, () -> P011PerformanceController.actionPermission("TARGET_STATUS"));
    }
}
