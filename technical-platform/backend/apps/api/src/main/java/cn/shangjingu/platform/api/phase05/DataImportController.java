package cn.shangjingu.platform.api.phase05;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.integration.DataImportService;
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
@RequestMapping("/api/v1/phase05/data-imports")
public final class DataImportController {
    private static final String READ = "phase05.p018.read";
    private static final String WRITE = "phase05.p018.write";

    private final DataImportService imports;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public DataImportController(
            DataImportService imports,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.imports = imports;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public DataImportService.DataImportJob create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataImportService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P018_CREATE_ATTEMPT", "integration.data_import_job", null);
        return imports.create(context(principal), idempotencyKey, hash(command), command);
    }

    @GetMapping("/{id}")
    public DataImportService.DataImportJob get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return imports.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("data import job not found"));
    }

    @GetMapping("/{id}/items")
    public List<DataImportService.ImportItem> items(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return imports.items(context(principal), id);
    }

    @PostMapping("/{id}/advance")
    public DataImportService.DataImportJob advance(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody AdvanceRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P018_ADVANCE_ATTEMPT", "integration.data_import_job", id);
        return imports.advance(context(principal), id, request.expectedVersion(), request.requestedStatus());
    }

    @PostMapping("/{id}/preview")
    public DataImportService.DataImportJob savePreview(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody PreviewRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P018_PREVIEW_ATTEMPT", "integration.data_import_job", id);
        return imports.savePreview(context(principal), id, request.expectedVersion(), request.preview());
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) {
            throw new AccessDeniedException("phase05 authorization denied: " + decision.reason());
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("request cannot be hashed", ex);
        }
    }

    public record AdvanceRequest(int expectedVersion, String requestedStatus) {
    }

    public record PreviewRequest(int expectedVersion, DataImportService.ValidationPreview preview) {
    }
}
