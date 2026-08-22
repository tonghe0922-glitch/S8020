package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
import cn.shangjingu.platform.workflow.phase11.RewardService;
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
@RequestMapping("/api/v1/processes/P013/rewards")
public class P013RewardController {
    public static final String CREATE = "p013.reward.create";
    public static final String READ = "p013.reward.read";
    public static final String REVIEW = "p013.reward.review";
    public static final String EXECUTE = "p013.reward.execute";
    public static final String MONITOR = "p013.reward.monitor";

    private static final Phase11ApiSupport.ReadPolicy<Phase11Record> READ_POLICY = Phase11ApiSupport.recordPolicy(
            "P013", List.of(READ), false, List.of(REVIEW, EXECUTE), MONITOR, "reward.reward_case");

    private final Phase11ApiSupport.Endpoint<RewardService.CreateCommand, RewardService.ActionCommand, Phase11Record>
            endpoint;

    public P013RewardController(RewardService rewards, Phase11ApiSupport support) {
        this.endpoint = support.endpoint(READ_POLICY, rewards::create, rewards::find, rewards::list, rewards::act);
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RewardService.CreateCommand command) {
        return endpoint.create(
                principal, CREATE, idempotencyKey, command, command.ownerCenterId(), command.ownerEmployeeId());
    }

    @GetMapping
    public List<Phase11Record> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return endpoint.list(principal);
    }

    @GetMapping("/{id}")
    public Phase11Record get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        return endpoint.get(principal, id, "P013 reward case not found");
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RewardService.ActionCommand command) {
        return endpoint.action(
                principal,
                id,
                actionCode,
                idempotencyKey,
                command,
                P013RewardController::actionPermission,
                "P013 reward case not found");
    }

    static String actionPermission(String action) {
        return Phase11PermissionCatalog.action("P013", action);
    }
}
