package cn.shangjingu.platform.org.application;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrgArchitecturePolicyTest {
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CHILD = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void normalizesAcyclicSnapshotInParentFirstOrder() {
        NodeView child = node(CHILD, "S06-ORG-002", "人力资源部", "DEPARTMENT", ROOT, 20);
        NodeView root = node(ROOT, "S06-ORG-001", "财人中心", "CENTER", null, 10);

        List<NodeView> normalized = OrgArchitectureValidator.normalizeSnapshot(List.of(child, root));

        assertEquals(List.of(ROOT, CHILD), normalized.stream().map(NodeView::id).toList());
        assertEquals("s06_org_001", normalized.getFirst().path());
        assertEquals("s06_org_001.s06_org_002", normalized.getLast().path());
    }

    @Test
    void rejectsCycleAndDuplicateCode() {
        NodeView left = node(ROOT, "DUPLICATE", "甲", "CENTER", CHILD, 10);
        NodeView right = node(CHILD, "DUPLICATE", "乙", "DEPARTMENT", ROOT, 20);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> OrgArchitectureValidator.normalizeSnapshot(List.of(left, right)));
        assertTrue(exception.getMessage().contains("duplicate orgCode"));

        NodeView cycleRight = node(CHILD, "CHILD", "乙", "DEPARTMENT", ROOT, 20);
        assertThrows(
                IllegalArgumentException.class,
                () -> OrgArchitectureValidator.normalizeSnapshot(List.of(left, cycleRight)));
    }

    @Test
    void calculatesAddedUpdatedAndRemovedChanges() {
        NodeView oldRoot = node(ROOT, "ROOT", "旧名称", "CENTER", null, 10);
        NodeView removed = node(CHILD, "REMOVED", "待停用", "DEPARTMENT", ROOT, 20);
        UUID addedId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        NodeView newRoot = node(ROOT, "ROOT", "新名称", "CENTER", null, 10);
        NodeView added = node(addedId, "ADDED", "新增部门", "DEPARTMENT", ROOT, 30);

        List<ChangeLine> changes =
                OrgArchitectureDiffCalculator.calculate(List.of(oldRoot, removed), List.of(newRoot, added));

        assertEquals(
                List.of("UPDATED", "ADDED", "REMOVED"),
                changes.stream().map(ChangeLine::kind).toList());
    }

    private static NodeView node(UUID id, String code, String name, String type, UUID parentId, int sortNo) {
        return new NodeView(id, code, name, type, parentId, "", "ACTIVE", null, 0, 0, sortNo, 0, null);
    }
}
