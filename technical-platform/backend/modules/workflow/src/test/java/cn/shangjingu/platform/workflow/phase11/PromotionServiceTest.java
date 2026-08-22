package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import org.junit.jupiter.api.Test;

class PromotionServiceTest {
    @Test
    void acceptsClosedPerformanceWithReviewFactsAboveThreshold() {
        assertDoesNotThrow(() ->
                PromotionService.validatePromotionGuard("CLOSED", "FINISHED", "QA_PASS", 3, 860, 800, false, "normal"));
    }

    @Test
    void rejectsIncompleteOrBelowThresholdEligibilityFacts() {
        assertThrows(
                ProcessRejectedException.class,
                () -> PromotionService.validatePromotionGuard(
                        "OPEN", "FINISHED", "QA_PASS", 3, 860, 800, false, "normal"));
        assertThrows(
                ProcessRejectedException.class,
                () -> PromotionService.validatePromotionGuard(
                        "CLOSED", "FINISHED", "QA_PASS", 0, 860, 800, false, "normal"));
        assertThrows(
                ProcessRejectedException.class,
                () -> PromotionService.validatePromotionGuard(
                        "CLOSED", "FINISHED", "QA_PASS", 3, 700, 800, false, "normal"));
    }

    @Test
    void belowThresholdOverrideRequiresCeoModeAndReasonToken() {
        assertThrows(
                ProcessRejectedException.class,
                () -> PromotionService.validatePromotionGuard(
                        "CLOSED", "FINISHED", "QA_PASS", 3, 700, 800, true, "approved"));
        assertDoesNotThrow(() -> PromotionService.validatePromotionGuard(
                "CLOSED", "IN_PROGRESS", "QA_PASS", 3, 700, 800, true, "[ceo_mode] exceptional appointment"));
    }
}
