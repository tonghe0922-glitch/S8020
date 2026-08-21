package cn.shangjingu.platform.api.platform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SecurityAuditUnavailableException;
import cn.shangjingu.platform.document.FileObjectService;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class PlatformFileDownloadGuardTest {
    private final AuthorizationService authorization=mock(AuthorizationService.class);
    private final StepUpService stepUp=mock(StepUpService.class);
    private final JdbcSecurityAuditService audit=mock(JdbcSecurityAuditService.class);
    private final SessionContext subject=subject();
    private final FileObjectService.FileObject file=file(subject.tenantId());
    private final AuthorizationTarget target=new AuthorizationTarget(subject.tenantId(),subject.employeeId(),subject.orgId(),subject.positionId(),subject.employeeId());

    @Test
    void permissionDataScopeMfa2AndCriticalAuditAreAllRequiredBeforeDownload() {
        when(authorization.authorizeAction(subject,PlatformFileDownloadGuard.PERMISSION)).thenReturn(AuthorizationDecision.allow(PlatformFileDownloadGuard.PERMISSION,"OWNER"));
        when(authorization.authorizeData(subject,PlatformFileDownloadGuard.PERMISSION,target)).thenReturn(AuthorizationDecision.allow(PlatformFileDownloadGuard.PERMISSION,"OWNER"));
        PlatformFileDownloadGuard guard=new PlatformFileDownloadGuard(authorization,stepUp,audit,ignored->Optional.of(target));
        try(FileDownloadAuthorizationContext.Scope ignored=FileDownloadAuthorizationContext.open(subject,"ticket-661")){guard.requireAllowed(file);}
        verify(stepUp).requireAndConsume("ticket-661",subject,PlatformFileDownloadGuard.STEP_UP_PURPOSE,2);
        verify(audit).recordSensitiveAccess(subject,"document.file_object",file.id(),"[\"content\"]","SIGNED_FILE_DOWNLOAD");
    }

    @Test
    void missingAuthoritativeDataTargetFailsClosedBeforeStepUpOrAudit() {
        when(authorization.authorizeAction(subject,PlatformFileDownloadGuard.PERMISSION)).thenReturn(AuthorizationDecision.allow(PlatformFileDownloadGuard.PERMISSION,"OWNER"));
        PlatformFileDownloadGuard guard=new PlatformFileDownloadGuard(authorization,stepUp,audit,ignored->Optional.empty());
        try(FileDownloadAuthorizationContext.Scope ignored=FileDownloadAuthorizationContext.open(subject,"ticket")){
            assertThrows(AccessDeniedException.class,()->guard.requireAllowed(file));
        }
        verifyNoInteractions(stepUp,audit);
    }

    @Test
    void immutableAuditFailureEscapesAndBlocksSignedUrlCreation() {
        when(authorization.authorizeAction(subject,PlatformFileDownloadGuard.PERMISSION)).thenReturn(AuthorizationDecision.allow(PlatformFileDownloadGuard.PERMISSION,"OWNER"));
        when(authorization.authorizeData(subject,PlatformFileDownloadGuard.PERMISSION,target)).thenReturn(AuthorizationDecision.allow(PlatformFileDownloadGuard.PERMISSION,"OWNER"));
        doThrow(new SecurityAuditUnavailableException(new IllegalStateException("audit offline"))).when(audit)
                .recordSensitiveAccess(subject,"document.file_object",file.id(),"[\"content\"]","SIGNED_FILE_DOWNLOAD");
        PlatformFileDownloadGuard guard=new PlatformFileDownloadGuard(authorization,stepUp,audit,ignored->Optional.of(target));
        try(FileDownloadAuthorizationContext.Scope ignored=FileDownloadAuthorizationContext.open(subject,"ticket")){
            assertThrows(SecurityAuditUnavailableException.class,()->guard.requireAllowed(file));
        }
    }

    private static SessionContext subject(){return new SessionContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Instant.now());}
    private static FileObjectService.FileObject file(UUID tenant){return new FileObjectService.FileObject(UUID.randomUUID(),tenant,"tenant/object","secret.pdf","application/pdf",12L,"a".repeat(64),"files","SAFE","P1_INTERNAL",1,Instant.now());}
}
