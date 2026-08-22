package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.workflow.phase11.PointLedgerService;
import cn.shangjingu.platform.workflow.phase11.PointLedgerView;
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
@RequestMapping("/api/v1/processes/P015/points")
public class P015PointsController {
    public static final String CREATE = "p015.points.create";
    public static final String READ = "p015.points.read";
    public static final String REVIEW = "p015.points.review";
    public static final String REVERSE = "p015.points.reverse";
    public static final String MONITOR = "p015.points.monitor";

    private static final Phase11ApiSupport.ReadPolicy<PointLedgerView> READ_POLICY = Phase11ApiSupport.viewPolicy(
            "P015",
            List.of(READ),
            false,
            List.of(REVIEW, REVERSE),
            MONITOR,
            "reward.point_transaction",
            PointLedgerView::id,
            PointLedgerView::ownerCenterId,
            PointLedgerView::ownerEmployeeId,
            PointLedgerView::metadataOnly);

    private final Phase11ApiSupport.Endpoint<
                    PointLedgerService.CreateCommand, PointLedgerService.ActionCommand, PointLedgerView>
            endpoint;

    public P015PointsController(PointLedgerService points, Phase11ApiSupport support) {
        this.endpoint = support.endpoint(READ_POLICY, points::create, points::find, points::list, points::act);
    }

    @PostMapping
    public PointLedgerView create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PointLedgerService.CreateCommand command) {
        return endpoint.create(
                principal, CREATE, idempotencyKey, command, command.ownerCenterId(), command.ownerEmployeeId());
    }

    @GetMapping
    public List<PointLedgerView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return endpoint.list(principal);
    }

    @GetMapping("/{id}")
    public PointLedgerView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        return endpoint.get(principal, id, "P015 point workflow case not found");
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public PointLedgerView action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PointLedgerService.ActionCommand command) {
        return endpoint.action(
                principal,
                id,
                actionCode,
                idempotencyKey,
                command,
                P015PointsController::actionPermission,
                "P015 point workflow case not found");
    }

    static String actionPermission(String action) {
        return Phase11PermissionCatalog.action("P015", action);
    }
}
