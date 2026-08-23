package cn.shangjingu.platform.org.application;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OrgArchitectureValidator {
    private static final Set<String> TYPES = Set.of("MANAGEMENT", "DIRECT", "COMPANY", "CENTER", "DEPARTMENT", "GROUP");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private OrgArchitectureValidator() {}

    public static void validate(NodeCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.orgCode(), 64, "orgCode");
        requireText(command.orgName(), 128, "orgName");
        requireType(command.orgType());
        requireStatus(command.status());
        if (command.headcountPlan() < 0) throw new IllegalArgumentException("headcountPlan must be non-negative");
        if (command.expectedVersion() < 0) throw new IllegalArgumentException("expectedVersion must be non-negative");
        if (command.description() != null && command.description().length() > 2000) {
            throw new IllegalArgumentException("description is too long");
        }
    }

    public static List<NodeView> normalizeSnapshot(List<NodeView> nodes) {
        List<NodeView> safe = nodes == null ? List.of() : List.copyOf(nodes);
        if (safe.isEmpty()) throw new IllegalArgumentException("architecture snapshot must contain at least one node");
        Map<UUID, NodeView> byId = new LinkedHashMap<>();
        Set<String> codes = new HashSet<>();
        for (NodeView node : safe) {
            validateNode(node);
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("architecture snapshot contains duplicate node id");
            }
            if (!codes.add(node.orgCode().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("architecture snapshot contains duplicate orgCode");
            }
        }
        Map<UUID, List<NodeView>> children = new HashMap<>();
        List<NodeView> roots = new ArrayList<>();
        for (NodeView node : safe) {
            if (node.parentId() == null) {
                roots.add(node);
            } else {
                if (!byId.containsKey(node.parentId())) {
                    throw new IllegalArgumentException("architecture snapshot references a missing parent");
                }
                children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>())
                        .add(node);
            }
        }
        if (roots.isEmpty()) throw new IllegalArgumentException("architecture snapshot must contain a root node");
        Comparator<NodeView> ordering = Comparator.comparingInt(NodeView::sortNo)
                .thenComparing(NodeView::orgCode)
                .thenComparing(NodeView::id);
        roots.sort(ordering);
        children.values().forEach(list -> list.sort(ordering));
        List<NodeView> normalized = new ArrayList<>(safe.size());
        Set<UUID> visiting = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        for (NodeView root : roots) {
            visit(root, null, children, visiting, visited, normalized);
        }
        if (visited.size() != safe.size()) throw new IllegalArgumentException("architecture snapshot contains a cycle");
        return List.copyOf(normalized);
    }

    private static void visit(
            NodeView node,
            String parentPath,
            Map<UUID, List<NodeView>> children,
            Set<UUID> visiting,
            Set<UUID> visited,
            List<NodeView> normalized) {
        if (!visiting.add(node.id())) throw new IllegalArgumentException("architecture snapshot contains a cycle");
        String path = parentPath == null ? pathLabel(node.orgCode()) : parentPath + "." + pathLabel(node.orgCode());
        normalized.add(new NodeView(
                node.id(),
                node.orgCode().trim(),
                node.orgName().trim(),
                node.orgType().trim().toUpperCase(Locale.ROOT),
                node.parentId(),
                path,
                node.status().trim().toUpperCase(Locale.ROOT),
                node.managerEmployeeId(),
                Math.max(0, node.memberCount()),
                node.headcountPlan(),
                node.sortNo(),
                Math.max(0, node.versionNo()),
                trimToNull(node.description())));
        for (NodeView child : children.getOrDefault(node.id(), List.of())) {
            visit(child, path, children, visiting, visited, normalized);
        }
        visiting.remove(node.id());
        visited.add(node.id());
    }

    private static void validateNode(NodeView node) {
        Objects.requireNonNull(node, "snapshot node");
        Objects.requireNonNull(node.id(), "snapshot node id");
        requireText(node.orgCode(), 64, "orgCode");
        requireText(node.orgName(), 128, "orgName");
        requireType(node.orgType());
        requireStatus(node.status());
        if (node.headcountPlan() < 0) throw new IllegalArgumentException("headcountPlan must be non-negative");
    }

    private static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) throw new IllegalArgumentException("orgType is invalid");
    }

    private static void requireStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) throw new IllegalArgumentException("status is invalid");
    }

    private static String pathLabel(String code) {
        String normalized = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) normalized = "org";
        if (Character.isDigit(normalized.charAt(0))) normalized = "o_" + normalized;
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
