package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisciplineServiceTest {
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000001114");
    private static final UUID SUBJECT = UUID.fromString("20000000-0000-0000-0000-000000001114");
    private static final UUID INVESTIGATOR = UUID.fromString("30000000-0000-0000-0000-000000001114");
    private static final UUID DECISION_MAKER = UUID.fromString("40000000-0000-0000-0000-000000001114");
    private static final UUID REVIEWER = UUID.fromString("50000000-0000-0000-0000-000000001114");

    @Test
    void p014GraphMatchesFrozenS01ToS12Contract() {
        Phase11Process process = Phase11Process.P014;
        assertEquals("CTR-P014-F01", process.initialFormCode());
        assertEquals(
                List.of(
                        "REGISTER_LEAD",
                        "APPLY_SAFETY_MEASURE",
                        "COMPLETE_INVESTIGATION",
                        "SUBMIT_DEFENSE",
                        "COMPLETE_RESPONSIBILITY_REVIEW",
                        "APPROVE_DECISION",
                        "ACKNOWLEDGE_SERVICE",
                        "EXECUTE_IMPACTS",
                        "RESOLVE_APPEAL",
                        "CLOSE_CORE_CASE",
                        "COMPLETE_OBSERVATION",
                        "ARCHIVE"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertEquals("S03", process.requireTransition("S02", "APPLY_SAFETY_MEASURE").targetNode());
        assertEquals("S10", process.requireTransition("S09", "RESOLVE_APPEAL").targetNode());
        assertEquals("S12", process.requireTransition("S11", "COMPLETE_OBSERVATION").targetNode());
        assertEquals("END", process.requireTransition("S12", "ARCHIVE").targetNode());
    }

    @Test
    void ordinaryInternalDisciplineDoesNotRequireCrmLink() {
        DisciplineService.CreateCommand command = command("INTERNAL", null, null);
        assertDoesNotThrow(() -> DisciplineService.validateCreate(command));
    }

    @Test
    void crmLinkIsAllowedOnlyForCustomerOriginatedCase() {
        DisciplineService.CreateCommand invalid = command("INTERNAL", "CRM-1", "Customer");
        assertThrows(ProcessRejectedException.class, () -> DisciplineService.validateCreate(invalid));
        DisciplineService.CreateCommand valid = command("CUSTOMER", "CRM-1", "Customer");
        assertDoesNotThrow(() -> DisciplineService.validateCreate(valid));
    }

    @Test
    void investigationDecisionAndAppealReviewEnforceSeparationOfDuty() {
        assertThrows(
                ProcessRejectedException.class,
                () -> DisciplineService.validateInvestigator(SUBJECT, SUBJECT));
        assertDoesNotThrow(() -> DisciplineService.validateInvestigator(SUBJECT, INVESTIGATOR));

        assertThrows(
                ProcessRejectedException.class,
                () -> DisciplineService.validateDecisionMaker(SUBJECT, SUBJECT));
        assertDoesNotThrow(() -> DisciplineService.validateDecisionMaker(SUBJECT, DECISION_MAKER));

        assertThrows(
                ProcessRejectedException.class,
                () -> DisciplineService.validateAppealReviewer(SUBJECT, DECISION_MAKER, SUBJECT));
        assertThrows(
                ProcessRejectedException.class,
                () -> DisciplineService.validateAppealReviewer(SUBJECT, DECISION_MAKER, DECISION_MAKER));
        assertDoesNotThrow(
                () -> DisciplineService.validateAppealReviewer(SUBJECT, DECISION_MAKER, REVIEWER));
    }

    private static DisciplineService.CreateCommand command(
            String sourceType, String customerId, String customerName) {
        return new DisciplineService.CreateCommand(
                "discipline case",
                "reason",
                "NORMAL",
                "HIGH",
                CENTER,
                SUBJECT,
                LocalDate.of(2026, 8, 16),
                Instant.parse("2026-08-16T00:00:00Z"),
                "fact",
                "P014-SOURCE-1",
                sourceType,
                customerId,
                customerName,
                "EMPLOYEE",
                null,
                "P014-CONTENT-V1",
                "2026-Q3");
    }
}
