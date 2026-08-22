package cn.shangjingu.platform.workflow.phase11;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Phase11Repository {
    Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode);

    Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode);

    List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId);

    boolean activeEmployeeInOrg(UUID tenantId, UUID orgId, UUID employeeId);

    void insert(Phase11Process process, Phase11Record record, Phase11CreateData data, UUID actorId);

    int bindWorkflow(
            Phase11Process process,
            UUID tenantId,
            UUID recordId,
            int expectedVersion,
            UUID workflowInstanceId,
            String nodeCode,
            String status,
            UUID actorId);

    int advance(
            Phase11Process process,
            Phase11Record current,
            String action,
            String targetNode,
            String status,
            Phase11ActionData data,
            UUID actorId);

    Optional<Phase11Record> find(Phase11Process process, UUID tenantId, UUID recordId);

    List<Phase11Record> list(Phase11Process process, UUID tenantId);

    int submitPerformanceScore(
            UUID tenantId,
            UUID cycleId,
            int expectedVersion,
            String scoreType,
            long score1000,
            String evidenceSummary,
            UUID actorId);

    PerformanceScores performanceScores(UUID tenantId, UUID cycleId);

    record FormRef(UUID id, int versionNo) {}

    record PerformanceScores(Long employee, Long supervisor, Long authoritative, Long calibrated) {
        public boolean readyForCalculation() {
            return employee != null && supervisor != null && authoritative != null;
        }

        public long calculated() {
            if (!readyForCalculation()) {
                throw new IllegalStateException("P011 source scores are incomplete");
            }
            return Math.round((employee + supervisor + authoritative) / 3.0d);
        }
    }
}
