package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P015PointsControllerContractTest {
    @Test
    void frozenPermissionsMapToServerActionsOnly() {
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("VALIDATE_SOURCE"));
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("CHECK_DUPLICATE"));
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("MATCH_RULE_VERSION"));
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("CALCULATE_POINTS"));
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("CLASSIFY_RISK"));
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("POST_OR_REVIEW"));
        assertEquals(P015PointsController.REVIEW, P015PointsController.actionPermission("NOTIFY_EMPLOYEE"));
        assertEquals(P015PointsController.REVERSE, P015PointsController.actionPermission("ADJUST_OR_REVERSE"));
        assertEquals(P015PointsController.REVERSE, P015PointsController.actionPermission("RECALCULATE_BALANCE"));
        assertThrows(IllegalArgumentException.class, () -> P015PointsController.actionPermission("TARGET_STATUS"));
    }
}
