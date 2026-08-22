package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.domain.IdentityRecord;
import cn.shangjingu.platform.iam.domain.UserAccountRecord;
import cn.shangjingu.platform.iam.mfa.TotpCredentialService;
import cn.shangjingu.platform.iam.session.SessionRejectedException;
import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.iam.session.SessionTokens;
import cn.shangjingu.platform.org.domain.AppointmentRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final IdentityDirectoryService identities;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final JdbcSecurityAuditService audit;
    private final TotpCredentialService totp;

    public LoginService(
            IdentityDirectoryService identities,
            SessionService sessions,
            PasswordEncoder passwordEncoder,
            JdbcSecurityAuditService audit,
            TotpCredentialService totp) {
        this.identities = identities;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.totp = totp;
    }

    public LoginOutcome login(
            String tenantCode, String loginName, String password, UUID requestedIdentityId, String mfaCode) {
        validateCredentials(tenantCode, loginName, password);
        UUID tenantId = identities
                .resolveTenant(tenantCode.strip())
                .orElseThrow(() -> rejected(LoginRejectedException.Reason.INVALID_CREDENTIALS));
        UserAccountRecord account =
                identities.findAccount(tenantId, loginName.strip()).orElse(null);
        requireValidAccount(tenantId, account, password);
        requireValidMfa(tenantId, account, mfaCode);

        List<IdentityRecord> activeIdentities = identities.activeIdentities(tenantId, account.id());
        IdentityRecord selected = selectIdentity(activeIdentities, requestedIdentityId);
        AppointmentRecord appointment = identities
                .activeAppointment(tenantId, selected)
                .orElseThrow(() -> new SessionRejectedException(SessionRejectedException.Reason.APPOINTMENT_INACTIVE));
        SessionTokens issued = sessions.issue(selected, appointment);
        completeLogin(tenantId, account.id(), issued);
        return new LoginOutcome(issued, List.copyOf(activeIdentities));
    }

    public void requirePasswordReauthentication(UUID tenantId, UUID userId, String password) {
        if (tenantId == null || userId == null || password == null || password.isEmpty()) {
            if (tenantId != null) {
                audit.recordSecurityEvent(
                        tenantId, userId, null, "P001_REAUTH_REJECTED", "WARN", "INVALID_CREDENTIALS");
            }
            throw rejected(LoginRejectedException.Reason.INVALID_CREDENTIALS);
        }
        UserAccountRecord account = identities.findAccountById(tenantId, userId).orElse(null);
        if (account == null || !account.active() || !matches(password, account.passwordHash())) {
            audit.recordSecurityEvent(tenantId, userId, null, "P001_REAUTH_REJECTED", "WARN", "INVALID_CREDENTIALS");
            throw rejected(LoginRejectedException.Reason.INVALID_CREDENTIALS);
        }
    }

    private void validateCredentials(String tenantCode, String loginName, String password) {
        if (tenantCode == null
                || tenantCode.isBlank()
                || loginName == null
                || loginName.isBlank()
                || password == null
                || password.isEmpty()) {
            throw rejected(LoginRejectedException.Reason.INVALID_CREDENTIALS);
        }
    }

    private void requireValidAccount(UUID tenantId, UserAccountRecord account, String password) {
        if (account != null && account.active() && matches(password, account.passwordHash())) return;
        audit.recordSecurityEvent(
                tenantId, account == null ? null : account.id(), null, "LOGIN_REJECTED", "WARN", "INVALID_CREDENTIALS");
        throw rejected(LoginRejectedException.Reason.INVALID_CREDENTIALS);
    }

    private void requireValidMfa(UUID tenantId, UserAccountRecord account, String mfaCode) {
        if (totp.verifyLogin(tenantId, account.id(), account.mfaLevel(), mfaCode)) return;
        audit.recordSecurityEvent(tenantId, account.id(), null, "LOGIN_REJECTED", "WARN", "MFA_REQUIRED_OR_INVALID");
        throw rejected(LoginRejectedException.Reason.MFA_REQUIRED_OR_INVALID);
    }

    private IdentityRecord selectIdentity(List<IdentityRecord> activeIdentities, UUID requestedIdentityId) {
        IdentityRecord selected = requestedIdentityId == null
                ? activeIdentities.stream().findFirst().orElse(null)
                : activeIdentities.stream()
                        .filter(identity -> identity.id().equals(requestedIdentityId))
                        .findFirst()
                        .orElse(null);
        if (selected == null) throw rejected(LoginRejectedException.Reason.NO_ACTIVE_IDENTITY);
        return selected;
    }

    private void completeLogin(UUID tenantId, UUID userId, SessionTokens issued) {
        try {
            identities.markLogin(tenantId, userId);
            audit.recordOperation(issued.context(), "LOGIN_SUCCESS", "SESSION", null);
        } catch (RuntimeException exception) {
            compensate(issued.accessToken());
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

    private boolean matches(String raw, String hash) {
        if (hash == null || hash.isBlank()) return false;
        try {
            return passwordEncoder.matches(raw, hash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private LoginRejectedException rejected(LoginRejectedException.Reason reason) {
        return new LoginRejectedException(reason);
    }

    public record LoginOutcome(SessionTokens tokens, List<IdentityRecord> activeIdentities) {}
}
