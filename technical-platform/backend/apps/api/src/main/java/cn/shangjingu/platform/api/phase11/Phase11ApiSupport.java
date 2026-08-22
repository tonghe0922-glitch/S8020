package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class Phase11ApiSupport {
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public Phase11ApiSupport(AuthorizationService authorization, JdbcSecurityAuditService audit, ObjectMapper mapper) {
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    public void requireAction(SessionPrincipal principal, String permission, String processCode) {
        require(authorization.authorizeAction(principal.context(), permission), processCode);
    }

    public void requireData(
            SessionPrincipal principal, String permission, AuthorizationTarget target, String processCode) {
        require(authorization.authorizeData(principal.context(), permission, target), processCode);
    }

    public boolean allowed(SessionPrincipal principal, String permission) {
        return authorization.authorizeAction(principal.context(), permission).allowed();
    }

    public boolean allowedData(SessionPrincipal principal, String permission, AuthorizationTarget target) {
        return authorization
                .authorizeData(principal.context(), permission, target)
                .allowed();
    }

    public AuthorizationTarget target(SessionPrincipal principal, Phase11Record record) {
        return target(principal, record.ownerCenterId(), record.ownerEmployeeId());
    }

    public AuthorizationTarget target(SessionPrincipal principal, UUID ownerCenterId, UUID ownerEmployeeId) {
        return new AuthorizationTarget(
                principal.context().tenantId(),
                ownerEmployeeId,
                ownerCenterId,
                principal.context().positionId(),
                ownerEmployeeId);
    }

    public DatabaseSecurityContext context(SessionPrincipal principal) {
        var session = principal.context();
        return new DatabaseSecurityContext(
                session.tenantId(),
                session.userId(),
                session.identityId(),
                session.employeeId(),
                session.appointmentId(),
                session.orgId(),
                session.positionId());
    }

    public String hash(Object value, String processCode) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalArgumentException(processCode + " request cannot be hashed", exception);
        }
    }

    public String safeAction(String value) {
        if (value == null) {
            return "INVALID";
        }
        String action = value.trim().toUpperCase(Locale.ROOT);
        return action.matches("[A-Z0-9_]{1,48}") ? action : "INVALID";
    }

    public static ReadPolicy<Phase11Record> recordPolicy(
            String processCode,
            List<String> readPermissions,
            boolean ownerOnlyRead,
            List<String> managePermissions,
            String monitorPermission,
            String resourceType) {
        return new ReadPolicy<>(
                processCode,
                readPermissions,
                ownerOnlyRead,
                managePermissions,
                monitorPermission,
                resourceType,
                Phase11Record::id,
                Phase11Record::ownerCenterId,
                Phase11Record::ownerEmployeeId,
                Phase11Record::metadataOnly);
    }

    public static <T> ReadPolicy<T> viewPolicy(
            String processCode,
            List<String> readPermissions,
            boolean ownerOnlyRead,
            List<String> managePermissions,
            String monitorPermission,
            String resourceType,
            Function<T, UUID> id,
            Function<T, UUID> ownerCenterId,
            Function<T, UUID> ownerEmployeeId,
            Function<T, T> metadataProjection) {
        return new ReadPolicy<>(
                processCode,
                readPermissions,
                ownerOnlyRead,
                managePermissions,
                monitorPermission,
                resourceType,
                id,
                ownerCenterId,
                ownerEmployeeId,
                metadataProjection);
    }

    public <C, A, T> Endpoint<C, A, T> endpoint(
            ReadPolicy<T> policy,
            CreateOperation<C, T> create,
            FindOperation<T> find,
            ListOperation<T> list,
            ActionOperation<A, T> action) {
        return new Endpoint<>(policy, create, find, list, action);
    }

    public void audit(SessionPrincipal principal, String action, String resourceType, UUID resourceId) {
        audit.recordOperation(principal.context(), action, resourceType, resourceId);
    }

    public <C, T> T create(
            SessionPrincipal principal,
            ReadPolicy<T> policy,
            String createPermission,
            String idempotencyKey,
            C command,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            CreateOperation<C, T> operation) {
        requireAction(principal, createPermission, policy.processCode());
        requireData(
                principal, createPermission, target(principal, ownerCenterId, ownerEmployeeId), policy.processCode());
        audit(principal, policy.processCode() + "_CREATE_ATTEMPT", policy.resourceType(), null);
        T result = operation.execute(context(principal), idempotencyKey, hash(command, policy.processCode()), command);
        audit(
                principal,
                policy.processCode() + "_CREATED",
                policy.resourceType(),
                policy.id().apply(result));
        return result;
    }

    public <C, T> T score(
            SessionPrincipal principal,
            ReadPolicy<T> policy,
            String permission,
            UUID id,
            String scoreType,
            String idempotencyKey,
            C command,
            T current,
            ScoreOperation<C, T> operation) {
        requireAction(principal, permission, policy.processCode());
        requireData(
                principal,
                permission,
                target(
                        principal,
                        policy.ownerCenterId().apply(current),
                        policy.ownerEmployeeId().apply(current)),
                policy.processCode());
        audit(principal, policy.processCode() + "_SCORE_ATTEMPT_" + scoreType, policy.resourceType(), id);
        T result = operation.execute(
                context(principal),
                id,
                scoreType,
                idempotencyKey,
                hash(Map.of("scoreType", scoreType, "body", command), policy.processCode()),
                command);
        audit(principal, policy.processCode() + "_SCORE_" + scoreType, policy.resourceType(), id);
        return result;
    }

    public <C, T> T action(
            SessionPrincipal principal,
            ReadPolicy<T> policy,
            String permission,
            UUID id,
            String action,
            String idempotencyKey,
            C command,
            T current,
            ActionOperation<C, T> operation) {
        requireAction(principal, permission, policy.processCode());
        requireData(
                principal,
                permission,
                target(
                        principal,
                        policy.ownerCenterId().apply(current),
                        policy.ownerEmployeeId().apply(current)),
                policy.processCode());
        audit(principal, policy.processCode() + "_ACTION_ATTEMPT_" + action, policy.resourceType(), id);
        T result = operation.execute(
                context(principal),
                id,
                action,
                idempotencyKey,
                hash(Map.of("action", action, "body", command), policy.processCode()),
                command);
        audit(principal, policy.processCode() + "_ACTION_" + action, policy.resourceType(), id);
        return result;
    }

    public <T> List<T> list(SessionPrincipal principal, List<T> records, ReadPolicy<T> policy) {
        ReadGrant grant = readGrant(principal, policy);
        return records.stream()
                .map(record -> project(principal, record, policy, grant))
                .filter(Objects::nonNull)
                .toList();
    }

    public <T> T get(SessionPrincipal principal, T record, ReadPolicy<T> policy) {
        T projected = project(principal, record, policy, readGrant(principal, policy));
        if (projected == null) {
            throw denied(policy.processCode(), "data scope denied");
        }
        audit(
                principal,
                policy.processCode() + "_READ",
                policy.resourceType(),
                policy.id().apply(record));
        return projected;
    }

    public <T> T required(Optional<T> value, String message) {
        return value.orElseThrow(() -> new IllegalArgumentException(message));
    }

    private <T> ReadGrant readGrant(SessionPrincipal principal, ReadPolicy<T> policy) {
        ReadGrant grant = new ReadGrant(
                allowedAny(principal, policy.readPermissions()),
                allowedAny(principal, policy.managePermissions()),
                allowed(principal, policy.monitorPermission()));
        if (!grant.read() && !grant.manage() && !grant.monitor()) {
            throw denied(policy.processCode(), "no read surface is granted");
        }
        return grant;
    }

    private <T> T project(SessionPrincipal principal, T record, ReadPolicy<T> policy, ReadGrant grant) {
        UUID ownerCenterId = policy.ownerCenterId().apply(record);
        UUID ownerEmployeeId = policy.ownerEmployeeId().apply(record);
        AuthorizationTarget target = target(principal, ownerCenterId, ownerEmployeeId);
        if (grant.manage() && allowedDataAny(principal, policy.managePermissions(), target)) {
            return record;
        }
        if (grant.read()
                && (!policy.ownerOnlyRead() || principal.context().employeeId().equals(ownerEmployeeId))
                && allowedDataAny(principal, policy.readPermissions(), target)) {
            return record;
        }
        if (grant.monitor() && allowedData(principal, policy.monitorPermission(), target)) {
            return policy.metadataProjection().apply(record);
        }
        return null;
    }

    private boolean allowedAny(SessionPrincipal principal, List<String> permissions) {
        return permissions.stream().anyMatch(permission -> allowed(principal, permission));
    }

    private boolean allowedDataAny(SessionPrincipal principal, List<String> permissions, AuthorizationTarget target) {
        return permissions.stream().anyMatch(permission -> allowedData(principal, permission, target));
    }

    private static void require(AuthorizationDecision decision, String processCode) {
        if (!decision.allowed()) {
            throw denied(processCode, decision.reason());
        }
    }

    private static AccessDeniedException denied(String processCode, String reason) {
        return new AccessDeniedException(processCode + " authorization denied: " + reason);
    }

    public final class Endpoint<C, A, T> {
        private final ReadPolicy<T> policy;
        private final CreateOperation<C, T> createOperation;
        private final FindOperation<T> findOperation;
        private final ListOperation<T> listOperation;
        private final ActionOperation<A, T> actionOperation;

        private Endpoint(
                ReadPolicy<T> policy,
                CreateOperation<C, T> createOperation,
                FindOperation<T> findOperation,
                ListOperation<T> listOperation,
                ActionOperation<A, T> actionOperation) {
            this.policy = policy;
            this.createOperation = createOperation;
            this.findOperation = findOperation;
            this.listOperation = listOperation;
            this.actionOperation = actionOperation;
        }

        public T create(
                SessionPrincipal principal,
                String permission,
                String idempotencyKey,
                C command,
                UUID ownerCenterId,
                UUID ownerEmployeeId) {
            return Phase11ApiSupport.this.create(
                    principal,
                    policy,
                    permission,
                    idempotencyKey,
                    command,
                    ownerCenterId,
                    ownerEmployeeId,
                    createOperation);
        }

        public List<T> list(SessionPrincipal principal) {
            return Phase11ApiSupport.this.list(principal, listOperation.execute(context(principal)), policy);
        }

        public T get(SessionPrincipal principal, UUID id, String notFoundMessage) {
            return Phase11ApiSupport.this.get(principal, required(principal, id, notFoundMessage), policy);
        }

        public T required(SessionPrincipal principal, UUID id, String notFoundMessage) {
            return Phase11ApiSupport.this.required(findOperation.execute(context(principal), id), notFoundMessage);
        }

        public T action(
                SessionPrincipal principal,
                UUID id,
                String actionCode,
                String idempotencyKey,
                A command,
                Function<String, String> permissionResolver,
                String notFoundMessage) {
            String action = safeAction(actionCode);
            return Phase11ApiSupport.this.action(
                    principal,
                    policy,
                    permissionResolver.apply(action),
                    id,
                    action,
                    idempotencyKey,
                    command,
                    required(principal, id, notFoundMessage),
                    actionOperation);
        }
    }

    public record ReadPolicy<T>(
            String processCode,
            List<String> readPermissions,
            boolean ownerOnlyRead,
            List<String> managePermissions,
            String monitorPermission,
            String resourceType,
            Function<T, UUID> id,
            Function<T, UUID> ownerCenterId,
            Function<T, UUID> ownerEmployeeId,
            Function<T, T> metadataProjection) {
        public ReadPolicy {
            Objects.requireNonNull(processCode);
            readPermissions = List.copyOf(readPermissions);
            managePermissions = List.copyOf(managePermissions);
            Objects.requireNonNull(monitorPermission);
            Objects.requireNonNull(resourceType);
            Objects.requireNonNull(id);
            Objects.requireNonNull(ownerCenterId);
            Objects.requireNonNull(ownerEmployeeId);
            Objects.requireNonNull(metadataProjection);
        }
    }

    private record ReadGrant(boolean read, boolean manage, boolean monitor) {}

    @FunctionalInterface
    public interface CreateOperation<C, T> {
        T execute(DatabaseSecurityContext context, String idempotencyKey, String requestHash, C command);
    }

    @FunctionalInterface
    public interface FindOperation<T> {
        Optional<T> execute(DatabaseSecurityContext context, UUID id);
    }

    @FunctionalInterface
    public interface ListOperation<T> {
        List<T> execute(DatabaseSecurityContext context);
    }

    @FunctionalInterface
    public interface ScoreOperation<C, T> {
        T execute(
                DatabaseSecurityContext context,
                UUID id,
                String scoreType,
                String idempotencyKey,
                String requestHash,
                C command);
    }

    @FunctionalInterface
    public interface ActionOperation<C, T> {
        T execute(
                DatabaseSecurityContext context,
                UUID id,
                String action,
                String idempotencyKey,
                String requestHash,
                C command);
    }
}
