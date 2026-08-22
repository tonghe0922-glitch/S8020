package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RewardServiceTest {
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000001113");
    private static final UUID EMPLOYEE = UUID.fromString("20000000-0000-0000-0000-000000001113");

    @Test
    void p013GraphMatchesFrozenContract() {
        assertEquals(
                List.of(
                        "REGISTER_CONTRIBUTION",
                        "VERIFY_EVIDENCE",
                        "RECOMMEND_REWARD",
                        "APPROVE_REWARD",
                        "CHECK_DUPLICATE_IMPACT",
                        "EXECUTE_REWARD",
                        "NOTIFY_EMPLOYEE",
                        "RECORD_RECEIPTS",
                        "ARCHIVE"),
                Phase11Process.P013.steps().stream()
                        .map(Phase11Process.Step::action)
                        .toList());
        assertEquals("END", Phase11Process.P013.steps().getLast().targetNode());
    }

    @Test
    void rewardRequiresConcreteImpactAndUniqueSourceKey() {
        RewardService.CreateCommand invalid = new RewardService.CreateCommand(
                "reward",
                "reason",
                "NORMAL",
                "NORMAL",
                CENTER,
                EMPLOYEE,
                LocalDate.of(2026, 8, 16),
                Instant.parse("2026-08-16T00:00:00Z"),
                "fact",
                "P013-CONTENT-V1",
                "2026-Q3",
                "source-1",
                "P013_REWARD",
                "CENTER",
                LocalDate.of(2026, 8, 17),
                0L,
                BigDecimal.ZERO,
                null);
        assertThrows(ProcessRejectedException.class, () -> RewardService.validateCreate(invalid));
    }

    @Test
    void rewardRecipientCannotReviewOrExecuteOwnReward() {
        Phase11Record record = new Phase11Record(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "P013",
                "P013-TEST",
                UUID.randomUUID(),
                "WF-TEST",
                "S02",
                "证据核验",
                1,
                "reward",
                "reason",
                "NORMAL",
                "NORMAL",
                CENTER,
                EMPLOYEE,
                LocalDate.of(2026, 8, 16),
                Instant.parse("2026-08-16T00:00:00Z"),
                "fact",
                null,
                Instant.parse("2026-08-16T00:00:00Z"),
                Instant.parse("2026-08-16T00:00:00Z"),
                null,
                JsonNodeFactory.instance.objectNode());
        assertThrows(ProcessRejectedException.class, () -> RewardService.validateActor(record, EMPLOYEE));
    }
}
