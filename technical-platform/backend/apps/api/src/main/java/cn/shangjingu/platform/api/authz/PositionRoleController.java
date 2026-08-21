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
@RequestMapping("/api/v1/authz/positions/{positionId}/roles")
public final class PositionRoleController {
    private final AuthzConfigurationService service;
    private final AuthzApiSupport support;

    public PositionRoleController(AuthzConfigurationService service, AuthzApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @GetMapping
    public List<PositionRoleView> list(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID positionId) {
        support.requireRead(principal);
        List<PositionRoleView> result = service.positionRoles(support.context(principal), positionId);
        support.auditRead(principal, "AUTHZ_POSITION_ROLE_LIST", "iam.position_role", positionId);
        return result;
    }

    @PutMapping
    public List<PositionRoleView> replace(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID positionId,
            @RequestHeader("X-Step-Up-Ticket") String stepUpTicket,
            @RequestBody List<PositionRoleSelection> selections) {
        support.requireWrite(principal, AuthzApiSupport.POSITION_ROLE_MANAGE, stepUpTicket);
        Mutation<List<PositionRoleView>> mutation = service.replacePositionRoles(
                support.context(principal), positionId, selections);
        support.auditMutation(
                principal, "AUTHZ_POSITION_ROLES_REPLACED", "iam.position_role", positionId, mutation);
        return mutation.after();
    }
}
