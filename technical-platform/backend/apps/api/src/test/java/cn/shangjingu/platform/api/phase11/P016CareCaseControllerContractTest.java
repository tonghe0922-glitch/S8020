package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P016CareCaseControllerContractTest {
    @Test
    void frozenPermissionsMapToServerActionsOnly() {
        assertEquals(P016CareCaseController.REVIEW, P016CareCaseController.actionPermission("VERIFY_ELIGIBILITY"));
        assertEquals(P016CareCaseController.CONFIRM, P016CareCaseController.actionPermission("AUTHORIZE_PRIVACY"));
        assertEquals(P016CareCaseController.REVIEW, P016CareCaseController.actionPermission("APPROVE_CARE"));
        assertEquals(P016CareCaseController.EXECUTE, P016CareCaseController.actionPermission("EXECUTE_BENEFIT"));
        assertEquals(P016CareCaseController.CONFIRM, P016CareCaseController.actionPermission("CONFIRM_RECEIPT"));
        assertEquals(P016CareCaseController.RECONCILE, P016CareCaseController.actionPermission("RECONCILE"));
        assertEquals(P016CareCaseController.EXECUTE, P016CareCaseController.actionPermission("ARCHIVE"));
        assertThrows(IllegalArgumentException.class,
                () -> P016CareCaseController.actionPermission("REGISTER_CARE_CASE"));
        assertThrows(IllegalArgumentException.class,
                () -> P016CareCaseController.actionPermission("TARGET_STATUS"));
    }
}
