package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.MeetingService.ActionItemInput;
import cn.shangjingu.platform.workflow.MeetingService.FormRef;
import cn.shangjingu.platform.workflow.MeetingService.Meeting;
import cn.shangjingu.platform.workflow.MeetingService.MeetingItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMeetingRepository implements MeetingService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcMeetingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode) {
        return jdbc
                .query(
                        """
            select v.id from workflow.wf_version v join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
             where v.tenant_id=? and d.process_code=? and d.enabled and not d.is_deleted and v.status='PUBLISHED' and not v.is_deleted
               and (v.effective_at is null or v.effective_at<=now()) order by v.version_no desc,v.created_at desc limit 1
            """,
                        (rs, n) -> rs.getObject(1, UUID.class),
                        tenantId,
                        processCode)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode) {
        return jdbc
                .query(
                        """
            select id,version_no from workflow.wf_form_definition where tenant_id=? and form_code=? and process_code=? and node_code=?
              and enabled and not is_deleted order by version_no desc,created_at desc,id desc limit 1
            """,
                        (rs, n) -> new FormRef(rs.getObject("id", UUID.class), rs.getInt("version_no")),
                        tenantId,
                        formCode,
                        processCode,
                        nodeCode)
                .stream()
                .findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId) {
        return jdbc.query(
                """
            select distinct ui.employee_id from iam.user_role ur
              join iam.role r on r.tenant_id=ur.tenant_id and r.id=ur.role_id and r.enabled and not r.is_deleted
              join iam.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id and not rp.is_deleted
              join iam.permission p on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
              join iam.user_identity ui on ui.tenant_id=ur.tenant_id and ui.user_id=ur.user_id and not ui.is_deleted
                and (ur.identity_id is null or ur.identity_id=ui.id) and ui.effective_start_at<=now() and (ui.effective_end_at is null or ui.effective_end_at>now())
             where ur.tenant_id=? and p.permission_code=? and not ur.is_deleted and ur.effective_start_at<=now()
               and (ur.effective_end_at is null or ur.effective_end_at>now()) and ui.org_id=? order by ui.employee_id
            """,
                (rs, n) -> rs.getObject(1, UUID.class),
                tenantId,
                permissionCode,
                orgId);
    }

    @Override
    public boolean areActiveEmployeesInOrg(UUID tenantId, UUID orgId, List<UUID> employeeIds) {
        for (UUID employeeId : employeeIds) {
            Boolean found = jdbc.queryForObject(
                    """
                select exists(select 1 from iam.user_identity ui join org.employee e on e.tenant_id=ui.tenant_id and e.id=ui.employee_id
                  where ui.tenant_id=? and ui.org_id=? and ui.employee_id=? and not ui.is_deleted and e.employment_status='ACTIVE' and not e.is_deleted
                    and ui.effective_start_at<=now() and (ui.effective_end_at is null or ui.effective_end_at>now()))
                """,
                    Boolean.class,
                    tenantId,
                    orgId,
                    employeeId);
            if (!Boolean.TRUE.equals(found)) return false;
        }
        return true;
    }

    @Override
    public void insertMeeting(Meeting m, UUID actorId) {
        int n = jdbc.update(
                """
            insert into collaboration.meeting(id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,updated_by,
              source_channel,business_date,subject,priority,owner_center_id,owner_employee_id,attendance_type,employee_event_type,
              issuer_host_id,official_content,official_subject,official_type,start_at,venue_channel,visibility_level)
            values(?,?,?,null,?,0,?,?,'PC',?,?,'NORMAL',?,?,?,?,?,?,?,?,?,?,?)
            """,
                m.id(),
                m.tenantId(),
                m.businessNo(),
                m.status(),
                actorId,
                actorId,
                m.businessDate(),
                m.officialSubject(),
                m.ownerCenterId(),
                m.ownerEmployeeId(),
                m.attendanceType(),
                m.employeeEventType(),
                m.issuerHostId(),
                m.officialContent(),
                m.officialSubject(),
                m.officialType(),
                ts(m.startAt()),
                m.venueChannel(),
                m.visibilityLevel());
        if (n != 1) throw new ProcessRejectedException("P006 canonical meeting insert failed");
    }

    @Override
    public void replaceAgenda(UUID tenantId, UUID meetingId, List<String> agendaItems, UUID actorId) {
        jdbc.update(
                "delete from collaboration.meeting_item where tenant_id=? and master_id=? and field_code='AGENDA' and not is_deleted",
                tenantId,
                meetingId);
        if (agendaItems == null) return;
        int seq = 0;
        for (String value : agendaItems) {
            if (value == null || value.isBlank()) continue;
            jdbc.update(
                    """
                insert into collaboration.meeting_item(id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_name,item_value_text,sort_no)
                values(gen_random_uuid(),?,?,?,?,'AGENDA',?,?,?,?)
                """,
                    tenantId,
                    actorId,
                    actorId,
                    meetingId,
                    seq,
                    value.trim(),
                    value.trim(),
                    seq++);
        }
    }

    @Override
    public void insertParticipants(UUID tenantId, UUID meetingId, List<UUID> participantIds, UUID actorId) {
        int seq = 0;
        for (UUID employeeId : participantIds) {
            jdbc.update(
                    """
                insert into collaboration.meeting_item(id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_name,
                  related_object_type,related_object_id,action_status,version_no,sort_no)
                values(gen_random_uuid(),?,?,?,?,'PARTICIPANT',?,'参会人员','org.employee',?,'PENDING',0,?)
                """,
                    tenantId,
                    actorId,
                    actorId,
                    meetingId,
                    seq,
                    employeeId,
                    seq++);
        }
    }

    @Override
    public int bindWorkflowAndMove(
            UUID tenantId, UUID meetingId, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId) {
        return jdbc.update(
                """
            update collaboration.meeting set workflow_instance_id=?,status=?,version_no=version_no+1,updated_by=?,updated_at=now()
             where tenant_id=? and id=? and version_no=? and employee_event_type='P006_MEETING' and not is_deleted
            """,
                workflowInstanceId,
                status,
                actorId,
                tenantId,
                meetingId,
                expectedVersion);
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
        return jdbc.update(
                """
            update collaboration.meeting set status=?,archived_at=coalesce(?,archived_at),actual_end_at=coalesce(?,actual_end_at),closed_at=coalesce(?,closed_at),
              version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and id=? and version_no=? and employee_event_type='P006_MEETING' and not is_deleted
            """,
                status,
                ts(archivedAt),
                ts(closedAt),
                ts(closedAt),
                actorId,
                tenantId,
                meetingId,
                expectedVersion);
    }

    @Override
    public int markPublished(UUID tenantId, UUID meetingId, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting set published_at=coalesce(published_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and employee_event_type='P006_MEETING' and not is_deleted",
                actorId,
                tenantId,
                meetingId);
    }

    @Override
    public int markMeetingHeld(UUID tenantId, UUID meetingId, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting set actual_start_at=coalesce(actual_start_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and employee_event_type='P006_MEETING' and not is_deleted",
                actorId,
                tenantId,
                meetingId);
    }

    @Override
    public int confirmMinutes(UUID tenantId, UUID meetingId, int expectedVersion, String text, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting set minutes_text=?,minutes_confirmed_at=now(),version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and id=? and version_no=? and minutes_confirmed_at is null and employee_event_type='P006_MEETING' and not is_deleted",
                text,
                actorId,
                tenantId,
                meetingId,
                expectedVersion);
    }

    @Override
    public int markAttendance(UUID tenantId, UUID itemId, int expectedVersion, String status, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting_item set action_status=?,version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and id=? and field_code='PARTICIPANT' and related_object_id=? and version_no=? and action_status='PENDING' and not is_deleted",
                status,
                actorId,
                tenantId,
                itemId,
                actorId,
                expectedVersion);
    }

    @Override
    public void replaceActionItems(UUID tenantId, UUID meetingId, List<ActionItemInput> items, UUID actorId) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from collaboration.meeting_item where tenant_id=? and master_id=? and field_code='ACTION_ITEM' and not is_deleted",
                Integer.class,
                tenantId,
                meetingId);
        if (existing != null && existing > 0) throw new ProcessRejectedException("P006 action items already generated");
        int seq = 0;
        for (ActionItemInput item : items) {
            int n = jdbc.update(
                    """
            insert into collaboration.meeting_item(id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_name,item_value_text,
              related_object_type,related_object_id,action_owner_employee_id,action_due_at,action_status,version_no,sort_no)
            values(gen_random_uuid(),?,?,?,?,'ACTION_ITEM',?,?,?,'org.employee',?,?,?,'OPEN',0,?)
            """,
                    tenantId,
                    actorId,
                    actorId,
                    meetingId,
                    seq,
                    item.title(),
                    item.title(),
                    item.ownerEmployeeId(),
                    item.ownerEmployeeId(),
                    ts(item.dueAt()),
                    seq++);
            if (n != 1) throw new ProcessRejectedException("P006 action-item insert failed");
        }
    }

    @Override
    public int submitActionEvidence(UUID tenantId, UUID itemId, int expectedVersion, String evidence, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting_item set execution_evidence=?,completed_at=now(),action_status='EXECUTED',version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and id=? and field_code='ACTION_ITEM' and action_owner_employee_id=? and version_no=? and action_status in ('OPEN','REWORK') and not is_deleted",
                evidence,
                actorId,
                tenantId,
                itemId,
                actorId,
                expectedVersion);
    }

    @Override
    public int returnActionItems(UUID tenantId, UUID meetingId, List<UUID> itemIds, UUID actorId) {
        int count = 0;
        for (UUID id : itemIds)
            count += jdbc.update(
                    "update collaboration.meeting_item set action_status='REWORK',accepted_at=null,accepted_by=null,rework_count=rework_count+1,version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and master_id=? and id=? and field_code='ACTION_ITEM' and action_status='EXECUTED' and not is_deleted",
                    actorId,
                    tenantId,
                    meetingId,
                    id);
        return count;
    }

    @Override
    public int acceptAllActionItems(UUID tenantId, UUID meetingId, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting_item set action_status='ACCEPTED',accepted_at=now(),accepted_by=?,version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and master_id=? and field_code='ACTION_ITEM' and action_status='EXECUTED' and not is_deleted",
                actorId,
                actorId,
                tenantId,
                meetingId);
    }

    @Override
    public int markOverdueFacts(UUID tenantId, UUID meetingId, Instant now, UUID actorId) {
        return jdbc.update(
                "update collaboration.meeting_item set escalated_at=coalesce(escalated_at,?),version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and master_id=? and field_code='ACTION_ITEM' and action_due_at<? and escalated_at is null and not is_deleted",
                ts(now),
                actorId,
                tenantId,
                meetingId,
                ts(now));
    }

    @Override
    public Optional<Meeting> findMeeting(UUID tenantId, UUID meetingId) {
        return jdbc
                .query(
                        selectMeeting(
                                "where m.tenant_id=? and m.id=? and m.employee_event_type='P006_MEETING' and not m.is_deleted"),
                        (rs, n) -> mapMeeting(rs),
                        tenantId,
                        meetingId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Meeting> listMeetings(UUID tenantId) {
        return jdbc.query(
                selectMeeting(
                        "where m.tenant_id=? and m.employee_event_type='P006_MEETING' and not m.is_deleted order by m.created_at desc,m.id desc"),
                (rs, n) -> mapMeeting(rs),
                tenantId);
    }

    @Override
    public List<MeetingItem> listItems(UUID tenantId, UUID meetingId) {
        return jdbc.query(
                """
        select id,master_id,field_code,item_seq,item_name,item_value_text,related_object_id,action_owner_employee_id,action_due_at,action_status,
          execution_evidence,completed_at,accepted_at,accepted_by,rework_count,escalated_at,version_no from collaboration.meeting_item
         where tenant_id=? and master_id=? and not is_deleted order by field_code,item_seq,id
        """,
                (rs, n) -> mapItem(rs),
                tenantId,
                meetingId);
    }

    private String selectMeeting(String suffix) {
        return """
        select m.id,m.tenant_id,m.business_no,m.workflow_instance_id,m.status,m.version_no,m.official_subject,m.official_type,m.official_content,
          m.attendance_type,m.employee_event_type,m.issuer_host_id,m.visibility_level,m.venue_channel,m.owner_center_id,m.owner_employee_id,
          m.business_date,m.start_at,m.published_at,m.minutes_text,m.minutes_confirmed_at,m.archived_at,m.actual_end_at,m.updated_at,
          wi.instance_no workflow_instance_no,wi.current_node_code from collaboration.meeting m left join workflow.wf_instance wi
          on wi.tenant_id=m.tenant_id and wi.id=m.workflow_instance_id and not wi.is_deleted
        """
                + suffix;
    }

    private Meeting mapMeeting(ResultSet rs) throws SQLException {
        return new Meeting(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_instance_no"),
                rs.getString("current_node_code"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("official_subject"),
                rs.getString("official_type"),
                rs.getString("official_content"),
                rs.getString("attendance_type"),
                rs.getString("employee_event_type"),
                rs.getString("issuer_host_id"),
                rs.getString("visibility_level"),
                rs.getString("venue_channel"),
                rs.getObject("owner_center_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class),
                localDate(rs, "business_date"),
                instant(rs, "start_at"),
                instant(rs, "published_at"),
                rs.getString("minutes_text"),
                instant(rs, "minutes_confirmed_at"),
                instant(rs, "archived_at"),
                instant(rs, "actual_end_at"),
                instant(rs, "updated_at"));
    }

    private MeetingItem mapItem(ResultSet rs) throws SQLException {
        return new MeetingItem(
                rs.getObject("id", UUID.class),
                rs.getObject("master_id", UUID.class),
                rs.getString("field_code"),
                rs.getInt("item_seq"),
                rs.getString("item_name"),
                rs.getString("item_value_text"),
                rs.getObject("related_object_id", UUID.class),
                rs.getObject("action_owner_employee_id", UUID.class),
                instant(rs, "action_due_at"),
                rs.getString("action_status"),
                rs.getString("execution_evidence"),
                instant(rs, "completed_at"),
                instant(rs, "accepted_at"),
                rs.getObject("accepted_by", UUID.class),
                rs.getInt("rework_count"),
                instant(rs, "escalated_at"),
                rs.getInt("version_no"));
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        Object v = rs.getObject(c);
        if (v == null) return null;
        if (v instanceof OffsetDateTime o) return o.toInstant();
        if (v instanceof Timestamp t) return t.toInstant();
        return ((java.time.ZonedDateTime) v).toInstant();
    }

    private static LocalDate localDate(ResultSet rs, String c) throws SQLException {
        Object v = rs.getObject(c);
        return v == null ? null : rs.getObject(c, LocalDate.class);
    }
}
