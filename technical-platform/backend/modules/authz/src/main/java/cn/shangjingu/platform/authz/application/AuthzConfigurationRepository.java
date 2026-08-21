package cn.shangjingu.platform.authz.application;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AuthzConfigurationRepository {
    List<ModuleView> modules(UUID tenantId);

    ModuleView module(UUID tenantId, UUID moduleId);

    void validateModuleReferences(UUID tenantId, UUID currentModuleId, ModuleCommand command);

    ModuleView createModule(UUID tenantId, UUID actorId, ModuleCommand command);

    ModuleView updateModule(UUID tenantId, UUID actorId, UUID moduleId, ModuleCommand command);

    List<PermissionView> modulePermissions(UUID tenantId, UUID moduleId);

    void validateModulePermissionSelections(UUID tenantId, List<PermissionSelection> selections);

    void replaceModulePermissions(
            UUID tenantId, UUID actorId, UUID moduleId, List<PermissionSelection> selections);

    List<OrgModuleView> orgModules(UUID tenantId, UUID orgId);

    void replaceOrgModules(
            UUID tenantId, UUID actorId, UUID orgId, List<OrgModuleSelection> selections);

    List<PositionRoleView> positionRoles(UUID tenantId, UUID positionId);

    void replacePositionRoles(
            UUID tenantId, UUID actorId, UUID positionId, List<PositionRoleSelection> selections);

    ReferenceData referenceData(UUID tenantId);

    void validatePreviewContext(UUID tenantId, UUID orgId, UUID positionId);

    Set<UUID> activePositionRoleIds(UUID tenantId, UUID positionId, Instant effectiveAt);

    List<RoleView> roles(UUID tenantId, Set<UUID> roleIds);

    List<ModuleView> enabledModules(UUID tenantId, UUID orgId, Instant effectiveAt);

    List<PermissionFact> permissionFacts(UUID tenantId, Set<UUID> roleIds);
}
