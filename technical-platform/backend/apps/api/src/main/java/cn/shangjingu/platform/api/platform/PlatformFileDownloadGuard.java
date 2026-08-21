package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.document.FileDownloadGuard;
import cn.shangjingu.platform.document.FileObjectService;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/** Permission + authoritative data-scope + MFA2 Step-Up + immutable critical audit gate. */
public final class PlatformFileDownloadGuard implements FileDownloadGuard {
    public static final String PERMISSION = "platform.file.download";
    public static final String STEP_UP_PURPOSE = "platform.file.download";
    public static final int REQUIRED_MFA_LEVEL = 2;
    private final AuthorizationService authorization;
    private final StepUpService stepUp;
    private final JdbcSecurityAuditService audit;
    private final FileDownloadAuthorizationTargetResolver targets;

    public PlatformFileDownloadGuard(AuthorizationService authorization, StepUpService stepUp,
                                     JdbcSecurityAuditService audit, FileDownloadAuthorizationTargetResolver targets) {
        this.authorization=Objects.requireNonNull(authorization);this.stepUp=Objects.requireNonNull(stepUp);this.audit=Objects.requireNonNull(audit);this.targets=Objects.requireNonNull(targets);
    }

    @Override
    public void requireAllowed(FileObjectService.FileObject file) {
        if (file == null) throw new AccessDeniedException("file is required");
        FileDownloadAuthorizationContext.Request request = FileDownloadAuthorizationContext.requireCurrent();
        SessionContext subject = request.subject();
        if (!Objects.equals(subject.tenantId(), file.tenantId())) throw new AccessDeniedException("file tenant mismatch");
        requireAllowed(authorization.authorizeAction(subject, PERMISSION));
        AuthorizationTarget target = targets.resolve(file).orElseThrow(() -> new AccessDeniedException("file data-scope target is unavailable"));
        requireAllowed(authorization.authorizeData(subject, PERMISSION, target));
        stepUp.requireAndConsume(request.stepUpTicket(), subject, STEP_UP_PURPOSE, REQUIRED_MFA_LEVEL);
        audit.recordSensitiveAccess(subject, "document.file_object", file.id(), "[\"content\"]", "SIGNED_FILE_DOWNLOAD");
    }

    private static void requireAllowed(AuthorizationDecision decision) {
        if (decision == null || !decision.allowed()) throw new AccessDeniedException("authorization denied: " + (decision == null ? "UNKNOWN" : decision.reason()));
    }
}
