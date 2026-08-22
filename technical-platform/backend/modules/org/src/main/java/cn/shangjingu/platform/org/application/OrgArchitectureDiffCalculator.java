package cn.shangjingu.platform.org.application;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OrgArchitectureDiffCalculator {
    private OrgArchitectureDiffCalculator() {}

    public static List<ChangeLine> calculate(List<NodeView> published, List<NodeView> proposed) {
        Map<UUID, NodeView> before = index(published);
        Map<UUID, NodeView> after = index(proposed);
        List<ChangeLine> changes = new ArrayList<>();
        for (NodeView node : proposed) {
            NodeView previous = before.get(node.id());
            if (previous == null) {
                changes.add(new ChangeLine("ADDED", node.id(), node.orgCode(), "新增组织：" + node.orgName()));
            } else if (changed(previous, node)) {
                changes.add(new ChangeLine("UPDATED", node.id(), node.orgCode(), "调整组织：" + node.orgName()));
            }
        }
        for (NodeView node : published) {
            if (!after.containsKey(node.id())) {
                changes.add(new ChangeLine("REMOVED", node.id(), node.orgCode(), "停用组织：" + node.orgName()));
            }
        }
        return List.copyOf(changes);
    }

    private static Map<UUID, NodeView> index(List<NodeView> nodes) {
        Map<UUID, NodeView> result = new LinkedHashMap<>();
        if (nodes != null) nodes.forEach(node -> result.put(node.id(), node));
        return result;
    }

    private static boolean changed(NodeView left, NodeView right) {
        return !Objects.equals(left.orgCode(), right.orgCode())
                || !Objects.equals(left.orgName(), right.orgName())
                || !Objects.equals(left.orgType(), right.orgType())
                || !Objects.equals(left.parentId(), right.parentId())
                || !Objects.equals(left.status(), right.status())
                || !Objects.equals(left.managerEmployeeId(), right.managerEmployeeId())
                || left.headcountPlan() != right.headcountPlan()
                || left.sortNo() != right.sortNo()
                || !Objects.equals(left.description(), right.description());
    }
}
