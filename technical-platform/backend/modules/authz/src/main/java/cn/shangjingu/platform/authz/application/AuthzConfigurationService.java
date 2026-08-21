package cn.shangjingu.platform.authz.application;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class AuthzConfigurationService {
    private final TenantTransactionRunner transactions;
    private final AuthzConfigurationRepository repository;

    public AuthzConfigurationService(
            TenantTransactionRunner transactions, AuthzConfigurationRepository repository) {
        this.transactions = transactions;
        this.repository = repository;
    }

    public List<ModuleView> modules(DatabaseSecurityContext actor) {
        return transactions.required(actor, () -> repository.modules(actor.tenantId()));
    }

    public ModuleView module(DatabaseSecurityContext actor, UUID moduleId) {
        return transactions.required(actor, () -> repository.module(actor.tenantId(), moduleId));
    }

    public Mutation<ModuleView> createModule(DatabaseSecurityContext actor, ModuleCommand command) {
        validate(command);
        ModuleView created = transactions.required(actor, () -> {
            repository.validateModuleReferences(actor.tenantId(), null, command);
            return repository.createModule(actor.tenantId(), actor.userId(), command);
        });
        return new Mutation<>(null, created);
    }

    public Mutation<ModuleView> updateModule(
            DatabaseSecurityContext actor, UUID moduleId, ModuleCommand command) {
        validate(command);
        return transactions.required(actor, () -> {
            repository.validateModuleReferences(actor.tenantId(), moduleId, command);
            ModuleView before = repository.module(actor.tenantId(), moduleId);
            ModuleView after = repository.updateModule(actor.tenantId(), actor.userId(), moduleId, command);
            return new Mutation<>(before, after);
        });
    }

    public List<PermissionView> modulePermissions(DatabaseSecurityContext actor, UUID moduleId) {
        return transactions.required(actor, () -> repository.modulePermissions(actor.tenantId(), moduleId));
    }

    public Mutation<List<PermissionView>> replaceModulePermissions(
            DatabaseSecurityContext actor, UUID moduleId, List<PermissionSelection> selections) {
        List<PermissionSelection> safe = selections == null ? List.of() : List.copyOf(selections);
        requireDistinct(safe.stream().map(PermissionSelection::permissionId).toList(), "permission");
        if (safe.stream().anyMatch(selection -> selection.capabilityType() == null)) {
            throw new IllegalArgumentException("capabilityType is required");
        }
        return transactions.required(actor, () -> {
            repository.validateModulePermissionSelections(actor.tenantId(), safe);
            List<PermissionView> before = repository.modulePermissions(actor.tenantId(), moduleId);
            repository.replaceModulePermissions(actor.tenantId(), actor.userId(), moduleId, safe);
            List<PermissionView> after = repository.modulePermissions(actor.tenantId(), moduleId);
            return new Mutation<>(before, after);
        });
    }

    public List<OrgModuleView> orgModules(DatabaseSecurityContext actor, UUID orgId) {
        return transactions.required(actor, () -> repository.orgModules(actor.tenantId(), orgId));
    }

    public Mutation<List<OrgModuleView>> replaceOrgModules(
            DatabaseSecurityContext actor, UUID orgId, List<OrgModuleSelection> selections) {
        List<OrgModuleSelection> safe = selections == null ? List.of() : List.copyOf(selections);
        requireDistinct(safe.stream().map(OrgModuleSelection::moduleId).toList(), "module");
        safe.forEach(selection -> requireRange(selection.effectiveStartAt(), selection.effectiveEndAt()));
        return transactions.required(actor, () -> {
            List<OrgModuleView> before = repository.orgModules(actor.tenantId(), orgId);
            repository.replaceOrgModules(actor.tenantId(), actor.userId(), orgId, safe);
            List<OrgModuleView> after = repository.orgModules(actor.tenantId(), orgId);
            return new Mutation<>(before, after);
        });
    }

    public List<PositionRoleView> positionRoles(DatabaseSecurityContext actor, UUID positionId) {
        return transactions.required(actor, () -> repository.positionRoles(actor.tenantId(), positionId));
    }

    public Mutation<List<PositionRoleView>> replacePositionRoles(
            DatabaseSecurityContext actor, UUID positionId, List<PositionRoleSelection> selections) {
        List<PositionRoleSelection> safe = selections == null ? List.of() : List.copyOf(selections);
        requireDistinct(safe.stream().map(PositionRoleSelection::roleId).toList(), "role");
        safe.forEach(selection -> requireRange(selection.effectiveStartAt(), selection.effectiveEndAt()));
        return transactions.required(actor, () -> {
            List<PositionRoleView> before = repository.positionRoles(actor.tenantId(), positionId);
            repository.replacePositionRoles(actor.tenantId(), actor.userId(), positionId, safe);
            List<PositionRoleView> after = repository.positionRoles(actor.tenantId(), positionId);
            return new Mutation<>(before, after);
        });
    }

    public ReferenceData referenceData(DatabaseSecurityContext actor) {
        return transactions.required(actor, () -> repository.referenceData(actor.tenantId()));
    }

    public PreviewResult preview(DatabaseSecurityContext actor, PreviewCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.orgId(), "orgId");
        Objects.requireNonNull(command.positionId(), "positionId");
        Instant at = command.effectiveAt() == null ? Instant.now() : command.effectiveAt();
        Set<UUID> direct = command.directRoleIds() == null
                ? Set.of()
                : new LinkedHashSet<>(command.directRoleIds());
        return transactions.required(actor, () -> {
            repository.validatePreviewContext(actor.tenantId(), command.orgId(), command.positionId());
            Set<UUID> positionRoleIds = repository.activePositionRoleIds(
                    actor.tenantId(), command.positionId(), at);
            Set<UUID> allRoleIds = new LinkedHashSet<>(positionRoleIds);
            allRoleIds.addAll(direct);
            List<RoleView> roles = repository.roles(actor.tenantId(), allRoleIds);
            if (roles.size() != allRoleIds.size()) {
                throw new IllegalArgumentException("one or more simulated roles are missing, disabled, or cross-tenant");
            }
            List<RoleSource> sources = new ArrayList<>();
            for (RoleView role : roles) {
                boolean fromPosition = positionRoleIds.contains(role.id());
                boolean fromDirect = direct.contains(role.id());
                String source = fromPosition && fromDirect
                        ? "POSITION+DIRECT_SIMULATION"
                        : fromPosition ? "POSITION" : "DIRECT_SIMULATION";
                sources.add(new RoleSource(
                        role.id(), role.roleCode(), role.roleName(), role.dataScopeCode(), source));
            }
            List<ModuleView> enabled = repository.enabledModules(actor.tenantId(), command.orgId(), at);
            List<PermissionFact> facts = repository.permissionFacts(actor.tenantId(), allRoleIds);
            return PermissionPreviewCalculator.calculate(
                    command.orgId(), command.positionId(), at, sources, enabled, facts);
        });
    }

    private static void validate(ModuleCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.moduleCode(), 64, "moduleCode");
        requireText(command.moduleName(), 128, "moduleName");
        if (command.processCodes() != null) {
            for (String processCode : command.processCodes()) {
                if (processCode == null || !processCode.matches("P\\d{3}")) {
                    throw new IllegalArgumentException("processCodes must use PNNN format");
                }
            }
        }
    }

    private static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireDistinct(List<UUID> ids, String label) {
        if (ids.stream().anyMatch(Objects::isNull) || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(label + " selections must be non-null and distinct");
        }
    }

    private static void requireRange(Instant start, Instant end) {
        Instant effectiveStart = start == null ? Instant.now() : start;
        if (end != null && !end.isAfter(effectiveStart)) {
            throw new IllegalArgumentException("effectiveEndAt must be after effectiveStartAt");
        }
    }
}
