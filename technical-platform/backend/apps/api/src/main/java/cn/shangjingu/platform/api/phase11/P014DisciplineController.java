package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.workflow.phase11.DisciplineService;
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
@RequestMapping("/api/v1/processes/P014/discipline-cases")
public class P014DisciplineController {
    public static final String CREATE = "p014.discipline.create";
    public static final String READ = "p014.discipline.read";
    public static final String INVESTIGATE = "p014.discipline.investigate";
    public static final String DECIDE = "p014.discipline.decide";
    public static final String APPEAL = "p014.discipline.appeal";
    public static final String REMEDIATE = "p014.discipline.remediate";
    public static final String MONITOR = "p014.discipline.monitor";

    private static final Phase11ApiSupport.ReadPolicy<Phase11Record> READ_POLICY = Phase11ApiSupport.recordPolicy(
            "P014",
            List.of(READ),
            false,
            List.of(INVESTIGATE, DECIDE, APPEAL, REMEDIATE),
            MONITOR,
            "reward.discipline_case");

    private final Phase11ApiSupport.Endpoint<
                    DisciplineService.CreateCommand, DisciplineService.ActionCommand, Phase11Record>
            endpoint;

    public P014DisciplineController(DisciplineService discipline, Phase11ApiSupport support) {
        this.endpoint =
                support.endpoint(READ_POLICY, discipline::create, discipline::find, discipline::list, discipline::act);
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DisciplineService.CreateCommand command) {
        return endpoint.create(
                principal, CREATE, idempotencyKey, command, command.ownerCenterId(), command.ownerEmployeeId());
    }

    @GetMapping
    public List<Phase11Record> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return endpoint.list(principal);
    }

    @GetMapping("/{id}")
    public Phase11Record get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        return endpoint.get(principal, id, "P014 discipline case not found");
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DisciplineService.ActionCommand command) {
        return endpoint.action(
                principal,
                id,
                actionCode,
                idempotencyKey,
                command,
                P014DisciplineController::actionPermission,
                "P014 discipline case not found");
    }

    static String actionPermission(String action) {
        return Phase11PermissionCatalog.action("P014", action);
    }
}
