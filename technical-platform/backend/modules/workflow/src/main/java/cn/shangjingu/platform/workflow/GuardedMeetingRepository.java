package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/** Production contract adapter for the P006 canonical meeting projection. */
@Primary
@Repository
public class GuardedMeetingRepository implements MeetingService.Repository {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcMeetingRepository delegate;

    public GuardedMeetingRepository(JdbcMeetingRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode) {
        return delegate.latestPublishedWorkflowVersion(tenantId, processCode);
    }

    @Override
    public Optional<MeetingService.FormRef> latestPublishedForm(
            UUID tenantId, String formCode, String processCode, String nodeCode) {
        return delegate.latestPublishedForm(tenantId, formCode, processCode, nodeCode);
    }

    @Override
    public List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId) {
        return delegate.permissionCandidates(tenantId, permissionCode, orgId);
    }

    @Override
    public boolean areActiveEmployeesInOrg(UUID tenantId, UUID orgId, List<UUID> employeeIds) {
        return delegate.areActiveEmployeesInOrg(tenantId, orgId, employeeIds);
    }

    @Override
    public void insertMeeting(MeetingService.Meeting meeting, UUID actorId) {
        delegate.insertMeeting(withCanonicalIssuerHost(meeting), actorId);
    }

    @Override
    public void replaceAgenda(
            UUID tenantId, UUID meetingId, List<String> agendaItems, UUID actorId) {
        delegate.replaceAgenda(tenantId, meetingId, agendaItems, actorId);
    }

    @Override
    public void insertParticipants(
            UUID tenantId, UUID meetingId, List<UUID> participantIds, UUID actorId) {
        delegate.insertParticipants(tenantId, meetingId, participantIds, actorId);
    }

    @Override
    public int bindWorkflowAndMove(
            UUID tenantId,
            UUID meetingId,
            int expectedVersion,
            UUID workflowInstanceId,
            String status,
            UUID actorId) {
        return delegate.bindWorkflowAndMove(
                tenantId, meetingId, expectedVersion, workflowInstanceId, status, actorId);
    }

    @Override
    public int moveStatus(
            UUID tenantId,
            UUID meetingId,
            int expectedVersion,
            String status,
            Instant archivedAt,
            Instant closedAt,
            UUID actorId) {
        return delegate.moveStatus(
                tenantId, meetingId, expectedVersion, status, archivedAt, closedAt, actorId);
    }

    @Override
    public int markPublished(UUID tenantId, UUID meetingId, UUID actorId) {
        return delegate.markPublished(tenantId, meetingId, actorId);
    }

    @Override
    public int markMeetingHeld(UUID tenantId, UUID meetingId, UUID actorId) {
        return delegate.markMeetingHeld(tenantId, meetingId, actorId);
    }

    @Override
    public int confirmMinutes(
            UUID tenantId,
            UUID meetingId,
            int expectedVersion,
            String text,
            UUID actorId) {
        return delegate.confirmMinutes(tenantId, meetingId, expectedVersion, text, actorId);
    }

    @Override
    public int markAttendance(
            UUID tenantId,
            UUID itemId,
            int expectedVersion,
            String status,
            UUID actorId) {
        return delegate.markAttendance(tenantId, itemId, expectedVersion, status, actorId);
    }

    @Override
    public void replaceActionItems(
            UUID tenantId,
            UUID meetingId,
            List<MeetingService.ActionItemInput> items,
            UUID actorId) {
        delegate.replaceActionItems(tenantId, meetingId, items, actorId);
    }

    @Override
    public int submitActionEvidence(
            UUID tenantId,
            UUID itemId,
            int expectedVersion,
            String evidence,
            UUID actorId) {
        return delegate.submitActionEvidence(
                tenantId, itemId, expectedVersion, evidence, actorId);
    }

    @Override
    public int returnActionItems(
            UUID tenantId, UUID meetingId, List<UUID> itemIds, UUID actorId) {
        return delegate.returnActionItems(tenantId, meetingId, itemIds, actorId);
    }

    @Override
    public int acceptAllActionItems(UUID tenantId, UUID meetingId, UUID actorId) {
        return delegate.acceptAllActionItems(tenantId, meetingId, actorId);
    }

    @Override
    public int markOverdueFacts(UUID tenantId, UUID meetingId, Instant now, UUID actorId) {
        return delegate.markOverdueFacts(tenantId, meetingId, now, actorId);
    }

    @Override
    public Optional<MeetingService.Meeting> findMeeting(UUID tenantId, UUID meetingId) {
        return delegate.findMeeting(tenantId, meetingId);
    }

    @Override
    public List<MeetingService.Meeting> listMeetings(UUID tenantId) {
        return delegate.listMeetings(tenantId);
    }

    @Override
    public List<MeetingService.MeetingItem> listItems(UUID tenantId, UUID meetingId) {
        return delegate.listItems(tenantId, meetingId);
    }

    static String canonicalIssuerHostId(String issuerHostId) {
        if (issuerHostId == null || issuerHostId.isBlank()) {
            throw new ProcessRejectedException("P006 issuer host reference is required");
        }
        String trimmed = issuerHostId.trim();
        String canonical = UUID_TEXT.matcher(trimmed).matches()
                ? trimmed.replace("-", "")
                : trimmed;
        if (canonical.length() > 32) {
            throw new ProcessRejectedException(
                    "P006 issuer host reference exceeds the canonical varchar(32) contract");
        }
        return canonical;
    }

    private static MeetingService.Meeting withCanonicalIssuerHost(
            MeetingService.Meeting meeting) {
        return new MeetingService.Meeting(
                meeting.id(),
                meeting.tenantId(),
                meeting.businessNo(),
                meeting.workflowInstanceId(),
                meeting.workflowInstanceNo(),
                meeting.currentNodeCode(),
                meeting.status(),
                meeting.versionNo(),
                meeting.officialSubject(),
                meeting.officialType(),
                meeting.officialContent(),
                meeting.attendanceType(),
                meeting.employeeEventType(),
                canonicalIssuerHostId(meeting.issuerHostId()),
                meeting.visibilityLevel(),
                meeting.venueChannel(),
                meeting.ownerCenterId(),
                meeting.ownerEmployeeId(),
                meeting.businessDate(),
                meeting.startAt(),
                meeting.publishedAt(),
                meeting.minutesText(),
                meeting.minutesConfirmedAt(),
                meeting.archivedAt(),
                meeting.actualEndAt(),
                meeting.updatedAt());
    }
}
