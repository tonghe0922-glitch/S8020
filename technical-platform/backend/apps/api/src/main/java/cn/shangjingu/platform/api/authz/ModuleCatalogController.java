package cn.shangjingu.platform.api.authz;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.authz.application.AuthzConfigurationService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authz/modules")
public final class ModuleCatalogController {
    private final AuthzConfigurationService service;
    private final AuthzApiSupport support;

    public ModuleCatalogController(AuthzConfigurationService service, AuthzApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @GetMapping
    public List<ModuleView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        support.requireRead(principal);
        List<ModuleView> result = service.modules(support.context(principal));
        support.auditRead(principal, "AUTHZ_MODULE_LIST", "iam.module", null);
        return result;
    }

    @GetMapping("/{moduleId}")
    public ModuleView get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID moduleId) {
        support.requireRead(principal);
        ModuleView result = service.module(support.context(principal), moduleId);
        support.auditRead(principal, "AUTHZ_MODULE_READ", "iam.module", moduleId);
        return result;
    }

    @GetMapping("/reference-data")
    public ReferenceData referenceData(@AuthenticationPrincipal SessionPrincipal principal) {
        support.requireRead(principal);
        ReferenceData result = service.referenceData(support.context(principal));
        support.auditRead(principal, "AUTHZ_REFERENCE_DATA_READ", "iam.permission", null);
        return result;
    }

    @PostMapping
    public ModuleView create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("X-Step-Up-Ticket") String stepUpTicket,
            @RequestBody ModuleCommand command) {
        support.requireWrite(principal, AuthzApiSupport.MODULE_MANAGE, stepUpTicket);
        Mutation<ModuleView> mutation = service.createModule(support.context(principal), command);
        support.auditMutation(
                principal, "AUTHZ_MODULE_CREATED", "iam.module", mutation.after().id(), mutation);
        return mutation.after();
    }

    @PutMapping("/{moduleId}")
    public ModuleView update(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID moduleId,
            @RequestHeader("X-Step-Up-Ticket") String stepUpTicket,
            @RequestBody ModuleCommand command) {
        support.requireWrite(principal, AuthzApiSupport.MODULE_MANAGE, stepUpTicket);
        Mutation<ModuleView> mutation = service.updateModule(
                support.context(principal), moduleId, command);
        support.auditMutation(
                principal, "AUTHZ_MODULE_UPDATED", "iam.module", moduleId, mutation);
        return mutation.after();
    }
}
