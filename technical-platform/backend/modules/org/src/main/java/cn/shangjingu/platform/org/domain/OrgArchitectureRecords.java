package cn.shangjingu.platform.org.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrgArchitectureRecords {
    private OrgArchitectureRecords() {}

    public enum DraftStatus {
        DRAFT,
        PENDING,
        APPROVED,
        REJECTED,
        PUBLISHED,
        ABANDONED
    }

    public record NodeView(
            UUID id,
            String orgCode,
            String orgName,
            String orgType,
            UUID parentId,
            String path,
            String status,
            UUID managerEmployeeId,
            int memberCount,
            int headcountPlan,
            int sortNo,
            long versionNo,
            String description) {}

    public record NodeCommand(
            String orgCode,
            String orgName,
            String orgType,
            UUID parentId,
            String status,
            UUID managerEmployeeId,
            int headcountPlan,
            int sortNo,
            long expectedVersion,
            String description) {}

    public record ToggleCommand(boolean active, boolean cascade) {}

    public record ChangeLine(String kind, UUID nodeId, String orgCode, String summary) {}

    public record DraftView(
            UUID id,
            String title,
            DraftStatus status,
            long baseVersion,
            long versionNo,
            List<NodeView> snapshot,
            List<ChangeLine> changes,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            UUID submittedBy,
            Instant submittedAt,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewComment,
            UUID publishedBy,
            Instant publishedAt,
            String publishKey) {}

    public record DraftCreateCommand(String title) {}

    public record DraftUpdateCommand(String title, long expectedVersion, List<NodeView> snapshot) {}

    public record DraftActionCommand(long expectedVersion) {}

    public record ReviewCommand(boolean approved, String comment, long expectedVersion) {}

    public record DirectoryMember(
            UUID employeeId,
            String employeeNo,
            String displayName,
            UUID orgId,
            String orgName,
            UUID positionId,
            String positionName) {}

    public record DirectoryView(
            long versionNo, Instant publishedAt, List<NodeView> organizations, List<DirectoryMember> members) {}

    public record Mutation<T>(T before, T after) {}
}
