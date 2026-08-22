package cn.shangjingu.platform.core.process;

import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyRegistry {
    private final JdbcTemplate jdbc;

    public IdempotencyRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotencyClaim claim(
            UUID tenantId,
            UUID actorId,
            String idempotencyKey,
            String requestHash,
            String resourceType,
            UUID proposedResourceId,
            Duration ttl) {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        requireText(resourceType, "resourceType");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        jdbc.update(
                "delete from core.idempotency_record where tenant_id=? and idempotency_key=? and expire_at <= now()",
                tenantId,
                idempotencyKey);
        IdempotencyClaim existing = existing(tenantId, idempotencyKey, requestHash, resourceType);
        if (existing != null) {
            return existing;
        }
        try {
            jdbc.update(
                    """
                    insert into core.idempotency_record(
                        id,tenant_id,created_by,updated_by,idempotency_key,request_hash,
                        resource_type,resource_id,expire_at)
                    values (?,?,?,?,?,?,?,?,now() + (? * interval '1 millisecond'))
                    """,
                    UUID.randomUUID(),
                    tenantId,
                    actorId,
                    actorId,
                    idempotencyKey,
                    requestHash,
                    resourceType,
                    proposedResourceId,
                    ttl.toMillis());
            return new IdempotencyClaim(proposedResourceId, false);
        } catch (DuplicateKeyException race) {
            IdempotencyClaim raced = existing(tenantId, idempotencyKey, requestHash, resourceType);
            if (raced != null) {
                return raced;
            }
            throw new ProcessRejectedException("idempotency key conflict", race);
        }
    }

    private IdempotencyClaim existing(UUID tenantId, String key, String requestHash, String resourceType) {
        return jdbc.query(
                """
                select request_hash,resource_type,resource_id
                from core.idempotency_record
                where tenant_id=? and idempotency_key=? and expire_at > now() and not is_deleted
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    if (!requestHash.equals(rs.getString("request_hash"))
                            || !resourceType.equals(rs.getString("resource_type"))) {
                        throw new ProcessRejectedException("idempotency key reused with different request");
                    }
                    UUID id = rs.getObject("resource_id", UUID.class);
                    if (id == null) {
                        throw new ProcessRejectedException("idempotency record has no resource id");
                    }
                    return new IdempotencyClaim(id, true);
                },
                tenantId,
                key);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
