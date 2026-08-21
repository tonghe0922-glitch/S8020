package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.iam.session.SessionTokens;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {
    private final LoginService loginService;
    private final SessionService sessions;
    private final SessionViewFactory sessionViews;
    private final JdbcSecurityAuditService audit;

    public AuthController(
            LoginService loginService,
            SessionService sessions,
            SessionViewFactory sessionViews,
            JdbcSecurityAuditService audit) {
        this.loginService = loginService;
        this.sessions = sessions;
        this.sessionViews = sessionViews;
        this.audit = audit;
    }

    @PostMapping("/login")
    public LoginBootstrapResponse login(@RequestBody LoginRequest request) {
        LoginService.LoginOutcome outcome = loginService.login(
                request.tenantCode(),
                request.loginName(),
                request.password(),
                request.identityId(),
                request.mfaCode());
        try {
            return LoginBootstrapResponse.from(
                    outcome.tokens(),
                    sessionViews.create(outcome.tokens().context(), outcome.activeIdentities()));
        } catch (RuntimeException exception) {
            compensate(outcome.tokens().accessToken());
            throw exception;
        }
    }

    @PostMapping("/refresh")
    public SessionTokenResponse refresh(@RequestBody RefreshRequest request) {
        SessionTokens refreshed = sessions.refresh(request.refreshToken());
        try {
            audit.recordOperation(refreshed.context(), "SESSION_REFRESH", "SESSION", null);
            return SessionTokenResponse.from(refreshed);
        } catch (RuntimeException exception) {
            compensate(refreshed.accessToken());
            throw exception;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal SessionPrincipal principal) {
        audit.recordOperation(principal.context(), "SESSION_LOGOUT", "SESSION", null);
        sessions.logout(principal.accessToken());
        return ResponseEntity.noContent().build();
    }

    private void compensate(String accessToken) {
        try {
            sessions.logout(accessToken);
        } catch (RuntimeException ignored) {
            // The original failure remains authoritative and raw credentials are never logged.
        }
    }

    public record LoginRequest(
            String tenantCode,
            String loginName,
            String password,
            UUID identityId,
            String mfaCode) {}

    public record RefreshRequest(String refreshToken) {}
}
