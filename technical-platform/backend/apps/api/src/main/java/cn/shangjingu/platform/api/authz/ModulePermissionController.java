package cn.shangjingu.platform.api.authz;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.authz.application.AuthzConfigurationService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authz/modules/{moduleId}/permissions")
public final class ModulePermissionController {
    private final AuthzConfigurationService service;
    private final AuthzApiSupport support;

    public ModulePermissionController(AuthzConfigurationService service, AuthzApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @GetMapping
    public List<PermissionView> list(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID moduleId) {
        support.requireRead(principal);
        List<PermissionView> result = service.modulePermissions(support.context(principal), moduleId);
        support.auditRead(principal, "AUTHZ_MODULE_PERMISSION_LIST", "iam.module_permission", moduleId);
        return result;
    }

    @PutMapping
    public List<PermissionView> replace(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID moduleId,
            @RequestHeader("X-Step-Up-Ticket") String stepUpTicket,
            @RequestBody List<PermissionSelection> selections) {
        support.requireWrite(principal, AuthzApiSupport.MODULE_MANAGE, stepUpTicket);
        Mutation<List<PermissionView>> mutation = service.replaceModulePermissions(
                support.context(principal), moduleId, selections);
        support.auditMutation(
                principal, "AUTHZ_MODULE_PERMISSIONS_REPLACED", "iam.module_permission", moduleId, mutation);
        return mutation.after();
    }
}
