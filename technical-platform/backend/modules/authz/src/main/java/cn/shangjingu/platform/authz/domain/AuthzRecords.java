package cn.shangjingu.platform.authz.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AuthzRecords {
    private AuthzRecords() {}

    public record ModuleView(
            UUID id,
            String moduleCode,
            String moduleName,
            String moduleGroup,
            UUID parentId,
            List<String> processCodes,
            int sortNo,
            String icon,
            boolean enabled,
            String remark,
            int permissionCount) {}

    public record ModuleCommand(
            String moduleCode,
            String moduleName,
            String moduleGroup,
            UUID parentId,
            List<String> processCodes,
            int sortNo,
            String icon,
            boolean enabled,
            String remark) {}

    public record PermissionView(
            UUID id,
            String permissionCode,
            String permissionName,
            String actionCode,
            String riskLevel,
            ModuleCapabilityType capabilityType) {}

    public record PermissionSelection(UUID permissionId, ModuleCapabilityType capabilityType) {}

    public record OrgModuleView(
            UUID moduleId,
            String moduleCode,
            String moduleName,
            String moduleGroup,
            boolean enabled,
            boolean inheritToChildren,
            Instant effectiveStartAt,
            Instant effectiveEndAt,
            String remark) {}

    public record OrgModuleSelection(
            UUID moduleId,
            boolean enabled,
            boolean inheritToChildren,
            Instant effectiveStartAt,
            Instant effectiveEndAt,
            String remark) {}

    public record RoleView(
            UUID id, String roleCode, String roleName, String roleType, String dataScopeCode, boolean enabled) {}

    public record PositionRoleView(
            UUID roleId,
            String roleCode,
            String roleName,
            String dataScopeCode,
            boolean selected,
            Instant effectiveStartAt,
            Instant effectiveEndAt,
            String grantSource) {}

    public record PositionRoleSelection(
            UUID roleId, Instant effectiveStartAt, Instant effectiveEndAt, String grantSource) {}

    public record OrganizationView(
            UUID id, String orgCode, String orgName, String orgType, UUID parentId, String path, String status) {}

    public record PositionView(UUID id, String positionCode, String positionName, UUID orgId, String status) {}

    public record ReferenceData(
            List<OrganizationView> organizations,
            List<PositionView> positions,
            List<RoleView> roles,
            List<PermissionView> permissions) {}

    public record RoleSource(UUID roleId, String roleCode, String roleName, String dataScopeCode, String source) {}

    public record PermissionFact(
            UUID roleId,
            String roleCode,
            String roleName,
            String dataScopeCode,
            UUID permissionId,
            String permissionCode,
            String permissionName,
            String actionCode,
            String riskLevel,
            UUID moduleId,
            String moduleCode,
            String moduleName,
            ModuleCapabilityType capabilityType) {}

    public record PermissionSimulation(
            String permissionCode,
            String permissionName,
            String actionCode,
            String riskLevel,
            ModuleCapabilityType capabilityType,
            Set<String> sourceRoles,
            Set<String> moduleCodes,
            boolean effective,
            String reason) {}

    public record PreviewCommand(UUID orgId, UUID positionId, List<UUID> directRoleIds, Instant effectiveAt) {}

    public record PreviewResult(
            UUID orgId,
            UUID positionId,
            Instant effectiveAt,
            List<RoleSource> roles,
            List<ModuleView> enabledModules,
            List<PermissionSimulation> permissions,
            Set<String> effectivePermissionCodes,
            Set<String> filteredPermissionCodes,
            Set<String> dataScopes,
            boolean persisted) {}

    public record Mutation<T>(T before, T after) {}
}
