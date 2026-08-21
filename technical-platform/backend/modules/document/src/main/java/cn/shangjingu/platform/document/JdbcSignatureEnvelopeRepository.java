package cn.shangjingu.platform.document;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSignatureEnvelopeRepository implements SignatureEnvelopeService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcSignatureEnvelopeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(
            SignatureEnvelopeService.Envelope e,
            UUID sourceFileId,
            List<SignatureEnvelopeService.PartyCommand> parties,
            UUID actorId) {
        jdbc.update("""
                insert into document.signature_envelope(
                    id,tenant_id,business_no,status,version_no,created_by,updated_by,envelope_no,document_hash,
                    template_version,signing_order,sign_deadline_at,sign_status,completed_file_id,actual_start_at,
                    authentication_method,business_date,document_type,result_summary)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                e.id(), e.tenantId(), e.businessNo(), e.status(), e.versionNo(), actorId, actorId, e.envelopeNo(),
                e.documentHash(), e.templateVersion(), e.signingOrder(), e.signDeadlineAt(), e.signStatus(),
                e.completedFileId(), e.actualStartAt(), e.authenticationMethod(), e.businessDate(), e.documentType(),
                e.resultSummary());
        link(e.tenantId(), e.id(), "source_document", sourceFileId, "SOURCE", true, actorId);
        for (SignatureEnvelopeService.PartyCommand p : parties) {
            jdbc.update("""
                    insert into document.signature_party(
                        id,tenant_id,created_by,updated_by,envelope_id,party_type,party_id,party_name,
                        sign_order,authentication_method,sign_status)
                    values (?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), e.tenantId(), actorId, actorId, e.id(), p.partyType(), p.partyId(),
                    p.partyName(), p.signOrder(), p.authenticationMethod(), "PENDING");
        }
    }

    @Override
    public Optional<SignatureEnvelopeService.Envelope> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id,tenant_id,business_no,envelope_no,status,version_no,document_hash,template_version,
                       signing_order,sign_deadline_at,sign_status,completed_file_id,authentication_method,
                       business_date,document_type,result_summary,actual_start_at,actual_end_at,created_by
                from document.signature_envelope
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public List<SignatureEnvelopeService.Party> parties(UUID tenantId, UUID envelopeId) {
        return jdbc.query("""
                select id,party_id,party_type,sign_order,sign_status,evidence_no
                from document.signature_party
                where tenant_id=? and envelope_id=? and not is_deleted
                order by sign_order
                """, (rs, n) -> new SignatureEnvelopeService.Party(
                        rs.getObject("id", UUID.class), rs.getObject("party_id", UUID.class), rs.getString("party_type"),
                        rs.getInt("sign_order"), rs.getString("sign_status"), rs.getString("evidence_no")),
                tenantId, envelopeId);
    }

    @Override
    public int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId) {
        return jdbc.update("""
                update document.signature_envelope
                set status=?,version_no=version_no+1,actual_end_at=coalesce(?,actual_end_at),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, actualEndAt, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public void recordEvidence(
            UUID tenantId,
            UUID envelopeId,
            SignatureEnvelopeService.CallbackEvidence evidence,
            UUID actorId) {
        link(tenantId, envelopeId, "completed_file", evidence.completedFileId(), "SIGNED_FILE", true, actorId);
        link(tenantId, envelopeId, "certificate", evidence.certificateFileId(), "CERTIFICATE", true, actorId);
        link(tenantId, envelopeId, "timestamp", evidence.timestampFileId(), "TIMESTAMP", true, actorId);
        link(tenantId, envelopeId, "provider_callback", evidence.callbackEvidenceFileId(), "CALLBACK", true, actorId);
        for (SignatureEnvelopeService.PartyEvidence party : evidence.partyEvidence()) {
            int updated = jdbc.update("""
                    update document.signature_party
                    set sign_status='SIGNED',signed_at=now(),evidence_no=?,updated_by=?,updated_at=now()
                    where tenant_id=? and envelope_id=? and party_id=? and not is_deleted
                    """, party.evidenceNo(), actorId, tenantId, envelopeId, party.partyId());
            if (updated != 1) {
                throw new ProcessRejectedException("signature callback party evidence does not match exactly one party");
            }
        }
    }

    @Override
    public int updateVerified(UUID tenantId, UUID id, int expectedVersion, UUID completedFileId, UUID actorId) {
        return jdbc.update("""
                update document.signature_envelope
                set status='S07',version_no=version_no+1,sign_status='SIGNED',completed_file_id=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S06' and not is_deleted
                """, completedFileId, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public boolean hasCompleteEvidence(UUID tenantId, UUID envelopeId) {
        Integer unsignedParties = jdbc.queryForObject("""
                select count(*) from document.signature_party
                where tenant_id=? and envelope_id=? and not is_deleted
                  and (sign_status <> 'SIGNED' or evidence_no is null or evidence_no='')
                """, Integer.class, tenantId, envelopeId);
        Integer evidenceFiles = jdbc.queryForObject("""
                select count(distinct field_code) from document.attachment_link
                where tenant_id=? and business_type='document.signature_envelope' and business_id=? and not is_deleted
                  and is_evidence and field_code in ('completed_file','certificate','timestamp','provider_callback')
                """, Integer.class, tenantId, envelopeId);
        Integer envelopeReady = jdbc.queryForObject("""
                select count(*) from document.signature_envelope
                where tenant_id=? and id=? and not is_deleted and sign_status='SIGNED' and completed_file_id is not null
                """, Integer.class, tenantId, envelopeId);
        return unsignedParties != null && unsignedParties == 0
                && evidenceFiles != null && evidenceFiles == 4
                && envelopeReady != null && envelopeReady == 1;
    }

    private void link(UUID tenantId, UUID envelopeId, String fieldCode, UUID fileId, String attachmentType, boolean evidence, UUID actorId) {
        jdbc.update("""
                insert into document.attachment_link(
                    id,tenant_id,created_by,updated_by,business_type,business_id,field_code,file_id,attachment_type,is_evidence)
                values (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, actorId, actorId, "document.signature_envelope", envelopeId,
                fieldCode, fileId, attachmentType, evidence);
    }

    private static SignatureEnvelopeService.Envelope map(ResultSet rs) throws SQLException {
        return new SignatureEnvelopeService.Envelope(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getString("envelope_no"), rs.getString("status"), rs.getInt("version_no"), rs.getString("document_hash"),
                rs.getString("template_version"), rs.getString("signing_order"), instant(rs, "sign_deadline_at"),
                rs.getString("sign_status"), rs.getObject("completed_file_id", UUID.class), rs.getString("authentication_method"),
                rs.getObject("business_date", java.time.LocalDate.class), rs.getString("document_type"),
                rs.getString("result_summary"), instant(rs, "actual_start_at"), instant(rs, "actual_end_at"),
                rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

@Component
final class JdbcFileEvidenceCapability implements SignatureEnvelopeService.FileEvidenceCapability {
    private final JdbcTemplate jdbc;
    private final String safeStatus;

    JdbcFileEvidenceCapability(JdbcTemplate jdbc, @Value("${platform.files.safe-virus-scan-status:CLEAN}") String safeStatus) {
        this.jdbc = jdbc;
        this.safeStatus = safeStatus;
    }

    @Override
    public void assertSafe(UUID tenantId, UUID fileId, String expectedSha256) {
        Integer count = expectedSha256 == null
                ? jdbc.queryForObject("""
                    select count(*) from document.file_object
                    where tenant_id=? and id=? and not is_deleted and virus_scan_status=?
                    """, Integer.class, tenantId, fileId, safeStatus)
                : jdbc.queryForObject("""
                    select count(*) from document.file_object
                    where tenant_id=? and id=? and not is_deleted and virus_scan_status=? and sha256=?
                    """, Integer.class, tenantId, fileId, safeStatus, expectedSha256);
        if (count == null || count != 1) {
            throw new ProcessRejectedException("file evidence is missing, hash-mismatched, or not virus-scan safe");
        }
    }
}
