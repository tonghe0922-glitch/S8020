package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P012PromotionControllerContractTest {
    @Test
    void actionsMapOnlyToApprovedPermissions() {
        assertEquals(P012PromotionController.REVIEW, P012PromotionController.actionPermission("PASS_ELIGIBILITY"));
        assertEquals(P012PromotionController.APPOINT, P012PromotionController.actionPermission("APPROVE_PROMOTION"));
        assertEquals(P012PromotionController.READ, P012PromotionController.actionPermission("CONFIRM_APPOINTMENT"));
        assertEquals(
                P012PromotionController.ACTIVATE, P012PromotionController.actionPermission("ACTIVATE_APPOINTMENT"));
        assertThrows(IllegalArgumentException.class, () -> P012PromotionController.actionPermission("TARGET_STATUS"));
    }
}
