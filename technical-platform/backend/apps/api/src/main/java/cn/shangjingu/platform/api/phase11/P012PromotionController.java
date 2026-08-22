package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
import cn.shangjingu.platform.workflow.phase11.PromotionService;
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
@RequestMapping("/api/v1/processes/P012/promotions")
public class P012PromotionController {
    public static final String CREATE = "p012.promotion.create";
    public static final String READ = "p012.promotion.read";
    public static final String REVIEW = "p012.promotion.review";
    public static final String APPOINT = "p012.promotion.appoint";
    public static final String ACTIVATE = "p012.promotion.activate";
    public static final String MONITOR = "p012.promotion.monitor";

    private static final Phase11ApiSupport.ReadPolicy<Phase11Record> READ_POLICY = Phase11ApiSupport.recordPolicy(
            "P012", List.of(READ), false, List.of(REVIEW, APPOINT, ACTIVATE), MONITOR, "hr.promotion_request");

    private final Phase11ApiSupport.Endpoint<
                    PromotionService.CreateCommand, PromotionService.ActionCommand, Phase11Record>
            endpoint;

    public P012PromotionController(PromotionService promotions, Phase11ApiSupport support) {
        this.endpoint =
                support.endpoint(READ_POLICY, promotions::create, promotions::find, promotions::list, promotions::act);
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PromotionService.CreateCommand command) {
        return endpoint.create(
                principal, CREATE, idempotencyKey, command, command.ownerCenterId(), command.ownerEmployeeId());
    }

    @GetMapping
    public List<Phase11Record> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return endpoint.list(principal);
    }

    @GetMapping("/{id}")
    public Phase11Record get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        return endpoint.get(principal, id, "P012 promotion request not found");
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PromotionService.ActionCommand command) {
        return endpoint.action(
                principal,
                id,
                actionCode,
                idempotencyKey,
                command,
                P012PromotionController::actionPermission,
                "P012 promotion request not found");
    }

    static String actionPermission(String action) {
        return Phase11PermissionCatalog.action("P012", action);
    }
}
