package cn.shangjingu.platform.api.phase10;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.DataScopeEvaluator;
import cn.shangjingu.platform.iam.domain.AuthorizationGrant;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.workflow.MeetingService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class P006MeetingControllerTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001010");
    private static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000001010");
    private static final UUID MANAGER = UUID.fromString("30000000-0000-0000-0000-000000001011");
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000001010");
    private static final UUID POSITION = UUID.fromString("20000000-0000-0000-0000-000000001010");

    @Test
    void participantActionUsesParticipantAsSelfScopeSubjectWithoutChangingMeetingOwner() {
        SessionContext context = new SessionContext(
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                EMPLOYEE,
                UUID.randomUUID(),
                CENTER,
                POSITION,
                Instant.parse("2026-08-14T08:00:00Z"));
        SessionPrincipal principal = new SessionPrincipal("token", context);

        var target = P006MeetingController.participantTarget(meeting(), principal);
        var selfGrant =
                new AuthorizationGrant(P006MeetingController.ACTION, "NORMAL", "SELF", "{\"scope\":\"SELF\"}", null);

        assertEquals(EMPLOYEE, target.employeeId());
        assertEquals(MANAGER, target.ownerEmployeeId());
        assertEquals(CENTER, target.orgId());
        assertTrue(new DataScopeEvaluator().allows(context, selfGrant, target));
    }

    private static MeetingService.Meeting meeting() {
        return new MeetingService.Meeting(
                UUID.randomUUID(),
                TENANT,
                "P006-TEST",
                UUID.randomUUID(),
                "WFI-P006-TEST",
                "S04",
                MeetingService.label("S04"),
                3,
                "PHASE-10 meeting",
                "OPERATIONS_REVIEW",
                "private",
                "ONSITE",
                "P006_MEETING",
                "30000000000000000000000000001011",
                "内部",
                "P006 room",
                CENTER,
                MANAGER,
                LocalDate.of(2031, 1, 10),
                Instant.parse("2031-01-10T09:00:00Z"),
                Instant.parse("2031-01-09T09:00:00Z"),
                null,
                null,
                null,
                null,
                Instant.parse("2026-08-14T08:00:00Z"));
    }
}
