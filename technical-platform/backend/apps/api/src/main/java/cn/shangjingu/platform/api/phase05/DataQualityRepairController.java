package cn.shangjingu.platform.api.phase05;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.audit.DataQualityRepairService;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/phase05/data-quality/issues")
public final class DataQualityRepairController {
    private static final String READ = "phase05.p020.read";
    private static final String WRITE = "phase05.p020.write";
    private static final String REPAIR = "phase05.p020.repair";

    private final DataQualityRepairService repairs;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public DataQualityRepairController(
            DataQualityRepairService repairs,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.repairs = repairs;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public DataQualityRepairService.QualityIssue create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityRepairService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P020_CREATE_ATTEMPT", "audit.data_quality_issue", null);
        return repairs.create(context(principal), idempotencyKey, hash(command), command);
    }

    @GetMapping("/{id}")
    public DataQualityRepairService.QualityIssue get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return repairs.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("data-quality issue not found"));
    }

    @GetMapping("/{id}/items")
    public List<DataQualityRepairService.QualityItem> items(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return repairs.items(context(principal), id);
    }

    @PostMapping("/{id}/advance")
    public DataQualityRepairService.QualityIssue advance(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody StateRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P020_ADVANCE_ATTEMPT", "audit.data_quality_issue", id);
        return repairs.advance(context(principal), id, request.expectedVersion(), request.requestedStatus());
    }

    @PostMapping("/{id}/approve-plan")
    public DataQualityRepairService.QualityIssue approvePlan(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody PlanRequest request) {
        require(authorization.authorizeAction(principal.context(), REPAIR));
        audit.recordOperation(principal.context(), "P020_REPAIR_PLAN_APPROVE_ATTEMPT", "audit.data_quality_issue", id);
        return repairs.approvePlan(context(principal), id, request.expectedVersion(), request.plan());
    }

    @PostMapping("/{id}/execute")
    public DataQualityRepairService.QualityIssue execute(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        require(authorization.authorizeAction(principal.context(), REPAIR));
        audit.recordOperation(principal.context(), "P020_REPAIR_EXECUTE_ATTEMPT", "audit.data_quality_issue", id);
        return repairs.executeRepair(context(principal), id, request.expectedVersion());
    }

    @PostMapping("/{id}/verify")
    public DataQualityRepairService.QualityIssue verify(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        require(authorization.authorizeAction(principal.context(), REPAIR));
        audit.recordOperation(principal.context(), "P020_REPAIR_VERIFY_ATTEMPT", "audit.data_quality_issue", id);
        return repairs.verify(context(principal), id, request.expectedVersion());
    }

    @PostMapping("/{id}/close")
    public DataQualityRepairService.QualityIssue close(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P020_CLOSE_ATTEMPT", "audit.data_quality_issue", id);
        return repairs.close(context(principal), id, request.expectedVersion());
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) throw new AccessDeniedException("phase05 authorization denied: " + decision.reason());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("request cannot be hashed", ex);
        }
    }

    public record StateRequest(int expectedVersion, String requestedStatus) {}
    public record VersionRequest(int expectedVersion) {}
    public record PlanRequest(int expectedVersion, Object plan) {}
}
