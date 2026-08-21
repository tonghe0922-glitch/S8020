package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.iam.session.SessionTokens;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public final class SessionController {
    private final SessionService sessions;
    private final SessionViewFactory sessionViews;
    private final JdbcSecurityAuditService audit;

    public SessionController(
            SessionService sessions,
            SessionViewFactory sessionViews,
            JdbcSecurityAuditService audit) {
        this.sessions = sessions;
        this.sessionViews = sessionViews;
        this.audit = audit;
    }

    @GetMapping
    public SessionViewResponse current(@AuthenticationPrincipal SessionPrincipal principal) {
        return sessionViews.create(principal.context());
    }

    @PostMapping("/switch")
    public SessionTokenResponse switchIdentity(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody SwitchRequest request) {
        SessionTokens switched = sessions.switchIdentity(principal.accessToken(), request.identityId());
        try {
            audit.recordOperation(switched.context(), "SESSION_SWITCH", "SESSION", null);
            return SessionTokenResponse.from(switched);
        } catch (RuntimeException exception) {
            compensate(switched.accessToken());
            throw exception;
        }
    }

    private void compensate(String accessToken) {
        try {
            sessions.logout(accessToken);
        } catch (RuntimeException ignored) {
            // The original failure remains authoritative and raw credentials are never logged.
        }
    }

    public record SwitchRequest(UUID identityId) {}
}
