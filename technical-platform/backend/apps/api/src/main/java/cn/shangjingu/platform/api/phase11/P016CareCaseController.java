package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.workflow.phase11.Phase11CareCaseService;
import cn.shangjingu.platform.workflow.phase11.Phase11CareCaseView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/processes/P016/care-cases")
public class P016CareCaseController {
    public static final String CREATE = "p016.care.create";
    public static final String READ = "p016.care.read";
    public static final String REVIEW = "p016.care.review";
    public static final String EXECUTE = "p016.care.execute";
    public static final String CONFIRM = "p016.care.confirm";
    public static final String RECONCILE = "p016.care.reconcile";
    public static final String MONITOR = "p016.care.monitor";

    private static final Phase11ApiSupport.ReadPolicy<Phase11CareCaseView> READ_POLICY = Phase11ApiSupport.viewPolicy(
            "P016",
            List.of(READ),
            false,
            List.of(REVIEW, EXECUTE, CONFIRM, RECONCILE),
            MONITOR,
            "welfare.care_case",
            Phase11CareCaseView::id,
            Phase11CareCaseView::ownerCenterId,
            Phase11CareCaseView::ownerEmployeeId,
            Phase11CareCaseView::metadataOnly);

    private final Phase11ApiSupport.Endpoint<
                    Phase11CareCaseService.CreateCommand, Phase11CareCaseService.ActionCommand, Phase11CareCaseView>
            endpoint;

    public P016CareCaseController(Phase11CareCaseService careCases, Phase11ApiSupport support) {
        this.endpoint =
                support.endpoint(READ_POLICY, careCases::create, careCases::find, careCases::list, careCases::act);
    }

    @PostMapping
    public Phase11CareCaseView create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Phase11CareCaseService.CreateCommand command) {
        return endpoint.create(
                principal, CREATE, idempotencyKey, command, command.ownerCenterId(), command.ownerEmployeeId());
    }

    @GetMapping
    public List<Phase11CareCaseView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return endpoint.list(principal);
    }

    @GetMapping("/{id}")
    public Phase11CareCaseView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        return endpoint.get(principal, id, "P016 care workflow case not found");
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11CareCaseView action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Phase11CareCaseService.ActionCommand command) {
        return endpoint.action(
                principal,
                id,
                actionCode,
                idempotencyKey,
                command,
                P016CareCaseController::actionPermission,
                "P016 care workflow case not found");
    }

    static String actionPermission(String action) {
        return Phase11PermissionCatalog.action("P016", action);
    }
}
