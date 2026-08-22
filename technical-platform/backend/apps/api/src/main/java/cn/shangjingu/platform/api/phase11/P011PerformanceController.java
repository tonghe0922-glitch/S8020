package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.workflow.phase11.PerformanceService;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
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
@RequestMapping("/api/v1/processes/P011/performance-cycles")
public class P011PerformanceController {
    public static final String CREATE = "p011.performance.create";
    public static final String READ = "p011.performance.read";
    public static final String SELF = "p011.performance.self";
    public static final String EVALUATE = "p011.performance.evaluate";
    public static final String CALIBRATE = "p011.performance.calibrate";
    public static final String APPEAL = "p011.performance.appeal";
    public static final String IMPACT = "p011.performance.impact";
    public static final String MONITOR = "p011.performance.monitor";

    private static final String NOT_FOUND = "P011 performance cycle not found";
    private static final Phase11ApiSupport.ReadPolicy<Phase11Record> READ_POLICY = Phase11ApiSupport.recordPolicy(
            "P011",
            List.of(SELF, READ),
            true,
            List.of(EVALUATE, CALIBRATE, APPEAL, IMPACT),
            MONITOR,
            "performance.performance_cycle");

    private final PerformanceService performance;
    private final Phase11ApiSupport support;
    private final Phase11ApiSupport.Endpoint<
                    PerformanceService.CreateCommand, PerformanceService.ActionCommand, Phase11Record>
            endpoint;

    public P011PerformanceController(PerformanceService performance, Phase11ApiSupport support) {
        this.performance = performance;
        this.support = support;
        this.endpoint = support.endpoint(
                READ_POLICY, performance::create, performance::find, performance::list, performance::act);
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PerformanceService.CreateCommand command) {
        return endpoint.create(
                principal, CREATE, idempotencyKey, command, command.ownerCenterId(), command.ownerEmployeeId());
    }

    @GetMapping
    public List<Phase11Record> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return endpoint.list(principal);
    }

    @GetMapping("/{id}")
    public Phase11Record get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        return endpoint.get(principal, id, NOT_FOUND);
    }

    @PostMapping("/{id}/scores/{scoreType}")
    public Phase11Record submitScore(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String scoreType,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PerformanceService.ScoreCommand command) {
        String normalized = support.safeAction(scoreType);
        return support.score(
                principal,
                READ_POLICY,
                scorePermission(normalized),
                id,
                normalized,
                idempotencyKey,
                command,
                endpoint.required(principal, id, NOT_FOUND),
                performance::submitScore);
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PerformanceService.ActionCommand command) {
        return endpoint.action(
                principal,
                id,
                actionCode,
                idempotencyKey,
                command,
                P011PerformanceController::actionPermission,
                NOT_FOUND);
    }

    static String scorePermission(String scoreType) {
        return Phase11PermissionCatalog.performanceScore(scoreType);
    }

    static String actionPermission(String action) {
        return Phase11PermissionCatalog.action("P011", action);
    }
}
