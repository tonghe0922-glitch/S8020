package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P013RewardControllerContractTest {
    @Test
    void frozenActionsMapOnlyToReviewOrExecutePermissions() {
        assertEquals(P013RewardController.REVIEW, P013RewardController.actionPermission("VERIFY_EVIDENCE"));
        assertEquals(P013RewardController.REVIEW, P013RewardController.actionPermission("CHECK_DUPLICATE_IMPACT"));
        assertEquals(P013RewardController.EXECUTE, P013RewardController.actionPermission("EXECUTE_REWARD"));
        assertEquals(P013RewardController.EXECUTE, P013RewardController.actionPermission("ARCHIVE"));
        assertThrows(
                IllegalArgumentException.class, () -> P013RewardController.actionPermission("CLIENT_TARGET_STATUS"));
    }
}
