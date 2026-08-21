package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PointLedgerServiceTest {
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000001115");
    private static final UUID EMPLOYEE = UUID.fromString("20000000-0000-0000-0000-000000001115");
    private static final UUID REVIEWER = UUID.fromString("30000000-0000-0000-0000-000000001115");

    @Test
    void p015GraphMatchesFrozenTenStepContract() {
        Phase11Process process = Phase11Process.P015;
        assertEquals("CTR-P015-F01", process.initialFormCode());
        assertEquals(List.of(
                "REGISTER_EVENT", "VALIDATE_SOURCE", "CHECK_DUPLICATE", "MATCH_RULE_VERSION",
                "CALCULATE_POINTS", "CLASSIFY_RISK", "POST_OR_REVIEW", "NOTIFY_EMPLOYEE",
                "ADJUST_OR_REVERSE", "RECALCULATE_BALANCE"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertEquals("S05", process.requireTransition("S04", "MATCH_RULE_VERSION").targetNode());
        assertEquals("END", process.requireTransition("S10", "RECALCULATE_BALANCE").targetNode());
    }

    @Test
    void createRequiresSourceEvidenceAndNeverAcceptsClientFinalPoints() {
        PointLedgerService.CreateCommand valid = command(JsonNodeFactory.instance.objectNode().put("reference", "SRC-1"));
        assertDoesNotThrow(() -> PointLedgerService.validateCreate(valid));
        PointLedgerService.CreateCommand invalid = command(JsonNodeFactory.instance.objectNode());
        assertThrows(ProcessRejectedException.class, () -> PointLedgerService.validateCreate(invalid));
    }

    @Test
    void subjectEmployeeCannotReviewOwnPointPosting() {
        assertThrows(ProcessRejectedException.class, () -> PointLedgerService.validateReviewer(EMPLOYEE, EMPLOYEE));
        assertDoesNotThrow(() -> PointLedgerService.validateReviewer(EMPLOYEE, REVIEWER));
    }

    private static PointLedgerService.CreateCommand command(com.fasterxml.jackson.databind.JsonNode evidence) {
        return new PointLedgerService.CreateCommand(
                "growth event", "source-backed event", "NORMAL", "NORMAL", CENTER, EMPLOYEE,
                LocalDate.of(2026, 8, 16), Instant.parse("2026-08-16T00:00:00Z"),
                "verified event", "P015-SOURCE-1", "INTERNAL", "GROWTH", "RULE-ATTENDANCE",
                evidence, "EMPLOYEE", "P015-CONTENT-V1", "2026-Q3");
    }
}
