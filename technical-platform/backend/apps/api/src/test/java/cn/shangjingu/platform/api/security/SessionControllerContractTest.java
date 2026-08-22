package cn.shangjingu.platform.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.domain.AuthorizationSnapshot;
import cn.shangjingu.platform.iam.domain.IdentityRecord;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.session.SessionService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionControllerContractTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000801");
    private static final UUID IDENTITY_A = UUID.fromString("20000000-0000-0000-0000-000000000801");
    private static final UUID IDENTITY_B = UUID.fromString("20000000-0000-0000-0000-000000000802");
    private static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000801");
    private static final UUID APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000801");
    private static final UUID ORG_A = UUID.fromString("50000000-0000-0000-0000-000000000801");
    private static final UUID ORG_B = UUID.fromString("50000000-0000-0000-0000-000000000802");
    private static final UUID POSITION_A = UUID.fromString("60000000-0000-0000-0000-000000000801");
    private static final UUID POSITION_B = UUID.fromString("60000000-0000-0000-0000-000000000802");

    @Mock
    SessionService sessions;

    @Mock
    IdentityDirectoryService identities;

    @Mock
    JdbcSecurityAuditService audit;

    @Test
    void currentSessionReturnsServerAuthorizedIdentityCandidatesAndSortedPermissions() {
        SessionContext context =
                new SessionContext(TENANT, USER, IDENTITY_A, EMPLOYEE, APPOINTMENT, ORG_A, POSITION_A, Instant.now());
        SessionPrincipal principal = new SessionPrincipal("synthetic-access-token", context);
        when(identities.authorization(context))
                .thenReturn(new AuthorizationSnapshot(
                        Set.of("platform.session.switch", "platform.session.read"), List.of()));
        when(identities.activeIdentities(TENANT, USER))
                .thenReturn(List.of(
                        identity(IDENTITY_B, ORG_B, POSITION_B, false, "Secondary identity"),
                        identity(IDENTITY_A, ORG_A, POSITION_A, true, "Primary identity")));

        SessionViewFactory sessionViews = new SessionViewFactory(identities);
        SessionController controller = new SessionController(sessions, sessionViews, audit);
        SessionViewResponse view = controller.current(principal);

        assertEquals(IDENTITY_A, view.identityId());
        assertEquals(List.of("platform.session.read", "platform.session.switch"), view.permissions());
        assertEquals(
                List.of(IDENTITY_A, IDENTITY_B),
                view.availableIdentities().stream()
                        .map(SessionViewResponse.AvailableIdentityView::identityId)
                        .toList());
        assertEquals("Primary identity", view.availableIdentities().getFirst().identityName());
        assertTrue(view.availableIdentities().getFirst().primary());
        verify(identities).activeIdentities(TENANT, USER);
    }

    private static IdentityRecord identity(UUID id, UUID orgId, UUID positionId, boolean primary, String name) {
        OffsetDateTime start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        return new IdentityRecord(
                id, TENANT, USER, EMPLOYEE, "POSITION", name, orgId, positionId, primary, start, null);
    }
}
