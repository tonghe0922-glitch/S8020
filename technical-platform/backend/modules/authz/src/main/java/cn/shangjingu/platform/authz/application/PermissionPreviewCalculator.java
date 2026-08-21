package cn.shangjingu.platform.authz.application;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import cn.shangjingu.platform.authz.domain.ModuleCapabilityType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PermissionPreviewCalculator {
    private PermissionPreviewCalculator() {}

    public static PreviewResult calculate(
            UUID orgId,
            UUID positionId,
            Instant effectiveAt,
            List<RoleSource> roleSources,
            List<ModuleView> enabledModules,
            List<PermissionFact> facts) {
        Set<UUID> enabledModuleIds = enabledModules.stream()
                .map(ModuleView::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Accumulator> byCode = new LinkedHashMap<>();
        for (PermissionFact fact : facts) {
            Accumulator current = byCode.computeIfAbsent(
                    fact.permissionCode(),
                    ignored -> new Accumulator(
                            fact.permissionCode(),
                            fact.permissionName(),
                            fact.actionCode(),
                            fact.riskLevel()));
            current.roles.add(fact.roleCode());
            if (fact.moduleId() != null) {
                current.moduleIds.add(fact.moduleId());
                current.moduleCodes.add(fact.moduleCode());
            }
            current.capability = strongest(current.capability, fact.capabilityType());
        }

        List<PermissionSimulation> permissions = new ArrayList<>();
        Set<String> effective = new LinkedHashSet<>();
        Set<String> filtered = new LinkedHashSet<>();
        for (Accumulator value : byCode.values()) {
            boolean unclassified = value.moduleIds.isEmpty();
            boolean moduleEnabled = unclassified || value.moduleIds.stream().anyMatch(enabledModuleIds::contains);
            String reason = unclassified
                    ? "未归属模块，迁移期兼容放行"
                    : moduleEnabled ? "角色权限存在且组织模块已启用" : "角色权限存在，但组织模块未启用";
            PermissionSimulation simulation = new PermissionSimulation(
                    value.code,
                    value.name,
                    value.action,
                    value.risk,
                    value.capability,
                    Set.copyOf(value.roles),
                    Set.copyOf(value.moduleCodes),
                    moduleEnabled,
                    reason);
            permissions.add(simulation);
            (moduleEnabled ? effective : filtered).add(value.code);
        }
        permissions.sort(Comparator.comparing(PermissionSimulation::permissionCode));
        Set<String> scopes = roleSources.stream()
                .map(RoleSource::dataScopeCode)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new PreviewResult(
                orgId,
                positionId,
                effectiveAt,
                List.copyOf(roleSources),
                List.copyOf(enabledModules),
                List.copyOf(permissions),
                Set.copyOf(effective),
                Set.copyOf(filtered),
                Set.copyOf(scopes),
                false);
    }

    private static ModuleCapabilityType strongest(
            ModuleCapabilityType left, ModuleCapabilityType right) {
        if (right == null) return left;
        if (left == null || right.ordinal() > left.ordinal()) return right;
        return left;
    }

    private static final class Accumulator {
        private final String code;
        private final String name;
        private final String action;
        private final String risk;
        private final Set<String> roles = new LinkedHashSet<>();
        private final Set<UUID> moduleIds = new LinkedHashSet<>();
        private final Set<String> moduleCodes = new LinkedHashSet<>();
        private ModuleCapabilityType capability;

        private Accumulator(String code, String name, String action, String risk) {
            this.code = code;
            this.name = name;
            this.action = action;
            this.risk = risk;
        }
    }
}
