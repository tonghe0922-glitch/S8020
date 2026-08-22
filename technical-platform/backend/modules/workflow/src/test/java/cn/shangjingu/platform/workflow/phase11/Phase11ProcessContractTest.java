package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase11ProcessContractTest {
    @Test
    void p011GraphMatchesFrozenContract() {
        Phase11Process process = Phase11Process.P011;
        assertEquals(
                List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11"),
                process.steps().stream().map(Phase11Process.Step::node).toList());
        assertEquals(
                List.of(
                        "SET_TARGETS",
                        "CONFIRM_TARGETS",
                        "RECORD_COACHING",
                        "COLLECT_FACTS",
                        "SUBMIT_REVIEWS",
                        "CALCULATE_SCORE",
                        "CALIBRATE",
                        "SUBMIT_APPEAL_DECISION",
                        "RESOLVE_APPEAL",
                        "EXECUTE_IMPACT",
                        "ARCHIVE"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertTrue(process.ownerAction("CONFIRM_TARGETS"));
        assertTrue(process.specialistAction("CALIBRATE"));
        assertFalse(process.ownerAction("CALCULATE_SCORE"));
        assertThrows(ProcessRejectedException.class, () -> process.requireTransition("S03", "CALIBRATE"));
    }

    @Test
    void p012GraphMatchesFrozenContract() {
        Phase11Process process = Phase11Process.P012;
        assertEquals(
                List.of(
                        "SUBMIT_NOMINATION",
                        "PASS_ELIGIBILITY",
                        "SUBMIT_ASSESSMENT",
                        "VERIFY_POSITION_BUDGET",
                        "COMPLETE_REVIEW",
                        "APPROVE_PROMOTION",
                        "COMPLETE_NOTICE",
                        "CONFIRM_APPOINTMENT",
                        "COMPLETE_VALIDATION",
                        "ACTIVATE_APPOINTMENT"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertTrue(process.ownerAction("CONFIRM_APPOINTMENT"));
        assertTrue(process.specialistAction("ACTIVATE_APPOINTMENT"));
        assertEquals(
                "END", process.requireTransition("S10", "ACTIVATE_APPOINTMENT").targetNode());
    }

    @Test
    void p014GraphMatchesFrozenTwelveStepContract() {
        Phase11Process process = Phase11Process.P014;
        assertEquals("CTR-P014-F01", process.initialFormCode());
        assertEquals("S05", process.requireTransition("S04", "SUBMIT_DEFENSE").targetNode());
        assertEquals("END", process.requireTransition("S12", "ARCHIVE").targetNode());
    }

    @Test
    void p016CheckpointExposesClosedAndCurrentPhase11ProcessesOnly() {
        assertEquals(
                List.of("P011", "P012", "P013", "P014", "P015", "P016"),
                java.util.Arrays.stream(Phase11Process.values())
                        .map(Phase11Process::code)
                        .toList());
    }

    @Test
    void p015GraphMatchesFrozenImmutableLedgerContract() {
        Phase11Process process = Phase11Process.P015;
        assertEquals(
                List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10"),
                process.steps().stream().map(Phase11Process.Step::node).toList());
        assertEquals(
                List.of(
                        "REGISTER_EVENT",
                        "VALIDATE_SOURCE",
                        "CHECK_DUPLICATE",
                        "MATCH_RULE_VERSION",
                        "CALCULATE_POINTS",
                        "CLASSIFY_RISK",
                        "POST_OR_REVIEW",
                        "NOTIFY_EMPLOYEE",
                        "ADJUST_OR_REVERSE",
                        "RECALCULATE_BALANCE"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertEquals("CTR-P015-F01", process.initialFormCode());
        assertEquals("S08", process.requireTransition("S07", "POST_OR_REVIEW").targetNode());
        assertEquals(
                "END", process.requireTransition("S10", "RECALCULATE_BALANCE").targetNode());
        assertThrows(ProcessRejectedException.class, () -> process.requireTransition("S07", "RECALCULATE_BALANCE"));
    }

    @Test
    void p016GraphMatchesFrozenReuseContract() {
        Phase11Process process = Phase11Process.P016;
        assertEquals("welfare.care_case", process.table());
        assertEquals("EMP-P016-F01", process.initialFormCode());
        assertEquals(
                List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08"),
                process.steps().stream().map(Phase11Process.Step::node).toList());
        assertEquals(
                List.of(
                        "REGISTER_CARE_CASE",
                        "VERIFY_ELIGIBILITY",
                        "AUTHORIZE_PRIVACY",
                        "APPROVE_CARE",
                        "EXECUTE_BENEFIT",
                        "CONFIRM_RECEIPT",
                        "RECONCILE",
                        "ARCHIVE"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertTrue(process.ownerAction("AUTHORIZE_PRIVACY"));
        assertTrue(process.ownerAction("CONFIRM_RECEIPT"));
        assertTrue(process.specialistAction("EXECUTE_BENEFIT"));
        assertTrue(process.specialistAction("RECONCILE"));
        assertEquals("END", process.requireTransition("S08", "ARCHIVE").targetNode());
        assertThrows(ProcessRejectedException.class, () -> process.requireTransition("S04", "EXECUTE_BENEFIT"));
    }

    @Test
    void independentScoresCalculateWithoutOverwritingSources() {
        Phase11Repository.PerformanceScores scores = new Phase11Repository.PerformanceScores(800L, 900L, 700L, null);
        assertTrue(scores.readyForCalculation());
        assertEquals(800L, scores.calculated());
        assertEquals(800L, scores.employee());
        assertEquals(900L, scores.supervisor());
        assertEquals(700L, scores.authoritative());
    }
}
