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
@RequestMapping("/api/v1/authz/orgs/{orgId}/modules")
public class OrgModuleController {
    private final AuthzConfigurationService service;
    private final AuthzApiSupport support;

    public OrgModuleController(AuthzConfigurationService service, AuthzApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @GetMapping
    public List<OrgModuleView> list(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID orgId) {
        support.requireRead(principal);
        List<OrgModuleView> result = service.orgModules(support.context(principal), orgId);
        support.auditRead(principal, "AUTHZ_ORG_MODULE_LIST", "iam.org_module", orgId);
        return result;
    }

    @PutMapping
    public List<OrgModuleView> replace(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID orgId,
            @RequestHeader("X-Step-Up-Ticket") String stepUpTicket,
            @RequestBody List<OrgModuleSelection> selections) {
        support.requireWrite(principal, AuthzApiSupport.ORG_MODULE_MANAGE, stepUpTicket);
        Mutation<List<OrgModuleView>> mutation =
                service.replaceOrgModules(support.context(principal), orgId, selections);
        support.auditMutation(principal, "AUTHZ_ORG_MODULES_REPLACED", "iam.org_module", orgId, mutation);
        return mutation.after();
    }
}
