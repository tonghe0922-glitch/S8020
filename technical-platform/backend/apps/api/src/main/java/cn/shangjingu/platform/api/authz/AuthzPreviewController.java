package cn.shangjingu.platform.api.authz;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.authz.application.AuthzConfigurationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authz/preview")
public final class AuthzPreviewController {
    private final AuthzConfigurationService service;
    private final AuthzApiSupport support;

    public AuthzPreviewController(AuthzConfigurationService service, AuthzApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @PostMapping
    public PreviewResult preview(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody PreviewCommand command) {
        support.requirePreview(principal);
        PreviewResult result = service.preview(support.context(principal), command);
        support.auditRead(principal, "AUTHZ_MULTI_ROLE_PREVIEW", "iam.role_permission", null);
        return result;
    }
}
