package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Phase11CareCaseServiceTest {
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000001116");
    private static final UUID EMPLOYEE = UUID.fromString("20000000-0000-0000-0000-000000001116");
    private static final UUID OTHER = UUID.fromString("30000000-0000-0000-0000-000000001116");

    @Test
    void p016GraphMatchesFrozenEightActionContract() {
        Phase11Process process = Phase11Process.P016;
        assertEquals("welfare.care_case", process.table());
        assertEquals("EMP-P016-F01", process.initialFormCode());
        assertEquals(List.of(
                "REGISTER_CARE_CASE", "VERIFY_ELIGIBILITY", "AUTHORIZE_PRIVACY", "APPROVE_CARE",
                "EXECUTE_BENEFIT", "CONFIRM_RECEIPT", "RECONCILE", "ARCHIVE"),
                process.steps().stream().map(Phase11Process.Step::action).toList());
        assertEquals("END", process.requireTransition("S08", "ARCHIVE").targetNode());
    }

    @Test
    void createRequiresSourceBackedCareFacts() {
        assertDoesNotThrow(() -> Phase11CareCaseService.validateCreate(validCommand()));
        Phase11CareCaseService.CreateCommand missingFact = new Phase11CareCaseService.CreateCommand(
                "关怀事项", "来源事实", "NORMAL", "NORMAL", CENTER, null, EMPLOYEE,
                LocalDate.of(2026, 8, 16), BigDecimal.ZERO, null, "CC-001", "CNY",
                Instant.parse("2026-08-16T00:00:00Z"), " ", null, "EMPLOYEE", "P016-CONTENT-V1", "2026-Q3");
        assertThrows(ProcessRejectedException.class, () -> Phase11CareCaseService.validateCreate(missingFact));
    }

    @Test
    void privacyAndReceiptAreRecipientOnlyActions() {
        Phase11CareCaseView view = view();
        assertDoesNotThrow(() -> Phase11CareCaseService.validateActionActor(view, "AUTHORIZE_PRIVACY", EMPLOYEE));
        assertDoesNotThrow(() -> Phase11CareCaseService.validateActionActor(view, "CONFIRM_RECEIPT", EMPLOYEE));
        assertThrows(ProcessRejectedException.class,
                () -> Phase11CareCaseService.validateActionActor(view, "AUTHORIZE_PRIVACY", OTHER));
        assertThrows(ProcessRejectedException.class,
                () -> Phase11CareCaseService.validateActionActor(view, "CONFIRM_RECEIPT", OTHER));
    }

    private static Phase11CareCaseService.CreateCommand validCommand() {
        return new Phase11CareCaseService.CreateCommand(
                "关怀事项", "来源事实", "NORMAL", "NORMAL", CENTER, null, EMPLOYEE,
                LocalDate.of(2026, 8, 16), BigDecimal.ZERO, null, "CC-001", "CNY",
                Instant.parse("2026-08-16T00:00:00Z"), "已核验关怀事实", null, "EMPLOYEE",
                "P016-CONTENT-V1", "2026-Q3");
    }

    private static Phase11CareCaseView view() {
        return new Phase11CareCaseView(
                UUID.randomUUID(), UUID.randomUUID(), "P016", "P016-TEST", UUID.randomUUID(),
                "S03", "S03", 2, "关怀事项", "来源事实", "NORMAL", "NORMAL",
                CENTER, null, EMPLOYEE, LocalDate.of(2026, 8, 16), BigDecimal.ZERO,
                null, "CC-001", "CNY", Instant.parse("2026-08-16T00:00:00Z"),
                "已核验关怀事实", "EMPLOYEE", null, null, null, null,
                Instant.parse("2026-08-16T00:00:00Z"), Instant.parse("2026-08-16T00:00:00Z"), List.of());
    }
}
