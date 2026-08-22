package cn.shangjingu.platform.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Tenant-scoped file object, scan-state, attachment and signed-download service. */
public final class FileObjectService {
    public enum ScanStatus {
        PENDING,
        SCANNING,
        SAFE,
        INFECTED,
        FAILED
    }

    private final JdbcTemplate jdbc;
    private final FileObjectStorage storage;
    private final FileDownloadGuard downloadGuard;

    public FileObjectService(JdbcTemplate jdbc, FileObjectStorage storage, FileDownloadGuard downloadGuard) {
        if (jdbc == null || storage == null || downloadGuard == null) {
            throw new IllegalArgumentException("file service dependencies are required");
        }
        this.jdbc = jdbc;
        this.storage = storage;
        this.downloadGuard = downloadGuard;
    }

    public UUID upload(UploadCommand command) {
        requireActiveTransaction();
        validateUpload(command);
        UUID fileId = UUID.randomUUID();
        String objectKey = command.tenantId() + "/" + fileId;
        String digest = sha256(command.content());
        storage.put(command.storageBucket(), objectKey, command.content(), command.contentType());
        boolean persisted = false;
        try {
            int inserted = jdbc.update(
                    """
                    insert into document.file_object(
                        id,tenant_id,created_by,updated_by,object_key,original_name,content_type,size_bytes,sha256,
                        storage_bucket,virus_scan_status,sensitive_level,version_no)
                    values (?,?,?,?,?,?,?,?,?,?, 'PENDING',?,?)
                    """,
                    fileId,
                    command.tenantId(),
                    command.actorId(),
                    command.actorId(),
                    objectKey,
                    command.originalName(),
                    command.contentType(),
                    (long) command.content().length,
                    digest,
                    command.storageBucket(),
                    command.sensitiveLevel(),
                    command.versionNo());
            if (inserted != 1) throw new IllegalStateException("file metadata insert failed");
            persisted = true;
            registerRollbackCleanup(command.storageBucket(), objectKey);
            return fileId;
        } finally {
            if (!persisted) safeRemove(command.storageBucket(), objectKey);
        }
    }

    public void transitionScan(UUID tenantId, UUID actorId, UUID fileId, ScanStatus target) {
        requireActiveTransaction();
        if (tenantId == null || fileId == null || target == null)
            throw new IllegalArgumentException("scan transition fields are required");
        FileObject current = lock(tenantId, fileId);
        ScanStatus from = ScanStatus.valueOf(current.virusScanStatus());
        if (!validTransition(from, target)) {
            throw new IllegalStateException("illegal file scan transition: " + from + " -> " + target);
        }
        int updated = jdbc.update(
                """
                update document.file_object
                set virus_scan_status=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and not is_deleted
                """,
                target.name(),
                actorId,
                tenantId,
                fileId);
        if (updated != 1) throw new IllegalStateException("file scan transition conflict");
    }

    public UUID bindAttachment(AttachmentCommand command) {
        requireActiveTransaction();
        validateAttachment(command);
        FileObject file = lock(command.tenantId(), command.fileId());
        requireSafe(file);
        UUID linkId = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                insert into document.attachment_link(
                    id,tenant_id,created_by,updated_by,business_type,business_id,field_code,file_id,
                    attachment_type,sort_no,is_evidence)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """,
                linkId,
                command.tenantId(),
                command.actorId(),
                command.actorId(),
                command.businessType(),
                command.businessId(),
                command.fieldCode(),
                command.fileId(),
                command.attachmentType(),
                command.sortNo(),
                command.evidence());
        if (inserted != 1) throw new IllegalStateException("attachment insert failed");
        return linkId;
    }

    public String presignDownload(UUID tenantId, UUID fileId, Duration ttl) {
        requireActiveTransaction();
        if (tenantId == null || fileId == null) throw new IllegalArgumentException("download identity is required");
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("download ttl must be between 1 second and 15 minutes");
        }
        FileObject file = find(tenantId, fileId);
        requireSafe(file);
        downloadGuard.requireAllowed(file);
        FileObjectStorage.StoredObject object = storage.stat(file.storageBucket(), file.objectKey());
        if (object.sizeBytes() != file.sizeBytes())
            throw new IllegalStateException("stored object size does not match metadata");
        return storage.presignGet(file.storageBucket(), file.objectKey(), ttl);
    }

    public FileObject find(UUID tenantId, UUID fileId) {
        FileObject file = jdbc.query(
                """
                select id,tenant_id,object_key,original_name,content_type,size_bytes,sha256,storage_bucket,
                       virus_scan_status,sensitive_level,version_no,created_at
                from document.file_object
                where tenant_id=? and id=? and not is_deleted
                """,
                rs -> rs.next() ? map(rs) : null,
                tenantId,
                fileId);
        if (file == null) throw new IllegalArgumentException("file not found");
        return file;
    }

    private FileObject lock(UUID tenantId, UUID fileId) {
        FileObject file = jdbc.query(
                """
                select id,tenant_id,object_key,original_name,content_type,size_bytes,sha256,storage_bucket,
                       virus_scan_status,sensitive_level,version_no,created_at
                from document.file_object
                where tenant_id=? and id=? and not is_deleted
                for update
                """,
                rs -> rs.next() ? map(rs) : null,
                tenantId,
                fileId);
        if (file == null) throw new IllegalArgumentException("file not found");
        return file;
    }

    private static FileObject map(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new FileObject(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("object_key"),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("storage_bucket"),
                rs.getString("virus_scan_status"),
                rs.getString("sensitive_level"),
                rs.getInt("version_no"),
                createdAt == null ? null : createdAt.toInstant());
    }

    private void registerRollbackCleanup(String bucket, String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) safeRemove(bucket, objectKey);
            }
        });
    }

    private void safeRemove(String bucket, String objectKey) {
        try {
            storage.remove(bucket, objectKey);
        } catch (RuntimeException ignored) {
            // Database rollback remains authoritative; object-store reconciliation can remove a rare orphan.
        }
    }

    private static void requireSafe(FileObject file) {
        if (!ScanStatus.SAFE.name().equals(file.virusScanStatus())) {
            throw new IllegalStateException("file is not SAFE and cannot be bound or downloaded");
        }
    }

    static boolean validTransition(ScanStatus from, ScanStatus to) {
        return switch (from) {
            case PENDING -> to == ScanStatus.SCANNING;
            case SCANNING -> to == ScanStatus.SAFE || to == ScanStatus.INFECTED || to == ScanStatus.FAILED;
            case FAILED -> to == ScanStatus.SCANNING;
            case SAFE, INFECTED -> false;
        };
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void validateUpload(UploadCommand command) {
        if (command == null
                || command.tenantId() == null
                || command.content() == null
                || command.content().length == 0) {
            throw new IllegalArgumentException("non-empty upload and tenant are required");
        }
        requireText(command.originalName(), "originalName", 255);
        requireText(command.contentType(), "contentType", 128);
        requireText(command.storageBucket(), "storageBucket", 128);
        requireText(command.sensitiveLevel(), "sensitiveLevel", 32);
        if (command.versionNo() <= 0) throw new IllegalArgumentException("versionNo must be positive");
    }

    private static void validateAttachment(AttachmentCommand command) {
        if (command == null || command.tenantId() == null || command.fileId() == null || command.businessId() == null) {
            throw new IllegalArgumentException("attachment tenant/file/business identity is required");
        }
        requireText(command.businessType(), "businessType", 128);
        if (command.fieldCode() != null && command.fieldCode().length() > 128)
            throw new IllegalArgumentException("fieldCode too long");
        if (command.attachmentType() != null && command.attachmentType().length() > 64)
            throw new IllegalArgumentException("attachmentType too long");
    }

    private static void requireText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(name + " is invalid");
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("file operation requires an active tenant transaction");
        }
    }

    public record UploadCommand(
            UUID tenantId,
            UUID actorId,
            String originalName,
            String contentType,
            String storageBucket,
            byte[] content,
            String sensitiveLevel,
            int versionNo) {}

    public record AttachmentCommand(
            UUID tenantId,
            UUID actorId,
            String businessType,
            UUID businessId,
            String fieldCode,
            UUID fileId,
            String attachmentType,
            int sortNo,
            boolean evidence) {}

    public record FileObject(
            UUID id,
            UUID tenantId,
            String objectKey,
            String originalName,
            String contentType,
            long sizeBytes,
            String sha256,
            String storageBucket,
            String virusScanStatus,
            String sensitiveLevel,
            int versionNo,
            Instant createdAt) {}
}
