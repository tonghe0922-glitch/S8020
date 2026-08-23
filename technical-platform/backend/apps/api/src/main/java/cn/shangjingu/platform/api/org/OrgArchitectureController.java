package cn.shangjingu.platform.api.org;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.org.application.OrgArchitectureService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/org")
public class OrgArchitectureController {
    private final OrgArchitectureService service;
    private final OrgArchitectureApiSupport support;

    public OrgArchitectureController(OrgArchitectureService service, OrgArchitectureApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @GetMapping("/tree")
    public List<NodeView> tree(@AuthenticationPrincipal SessionPrincipal principal) {
        support.requireView(principal);
        List<NodeView> result = service.tree(support.context(principal));
        support.auditRead(principal, "ORG_TREE_READ", "org.organization", null);
        return result;
    }

    @GetMapping("/directory")
    public DirectoryView directory(@AuthenticationPrincipal SessionPrincipal principal) {
        DirectoryView result = service.directory(support.context(principal));
        support.auditRead(principal, "ORG_DIRECTORY_READ", "org.organization", null);
        return result;
    }

    @PostMapping("/nodes")
    public NodeView createNode(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NodeCommand command) {
        support.requireManage(principal);
        Mutation<NodeView> mutation = service.createNode(support.context(principal), idempotencyKey, command);
        support.auditMutation(
                principal,
                "ORG_NODE_CREATE",
                "org.organization",
                mutation.after().id(),
                mutation);
        return mutation.after();
    }

    @PutMapping("/nodes/{nodeId}")
    public NodeView updateNode(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID nodeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NodeCommand command) {
        support.requireManage(principal);
        Mutation<NodeView> mutation = service.updateNode(support.context(principal), nodeId, idempotencyKey, command);
        support.auditMutation(principal, "ORG_NODE_UPDATE", "org.organization", nodeId, mutation);
        return mutation.after();
    }

    @PostMapping("/nodes/{nodeId}/toggle")
    public NodeView toggleNode(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID nodeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ToggleCommand command) {
        support.requireManage(principal);
        Mutation<NodeView> mutation = service.toggleNode(support.context(principal), nodeId, idempotencyKey, command);
        support.auditMutation(principal, "ORG_NODE_TOGGLE", "org.organization", nodeId, mutation);
        return mutation.after();
    }

    @DeleteMapping("/nodes/{nodeId}")
    public NodeView deleteNode(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID nodeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        support.requireManage(principal);
        Mutation<NodeView> mutation = service.deleteNode(support.context(principal), nodeId, idempotencyKey);
        support.auditMutation(principal, "ORG_NODE_DELETE", "org.organization", nodeId, mutation);
        return mutation.after();
    }
}
