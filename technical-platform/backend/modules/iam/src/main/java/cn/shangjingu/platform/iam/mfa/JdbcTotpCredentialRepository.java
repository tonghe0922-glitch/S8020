package cn.shangjingu.platform.iam.mfa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTotpCredentialRepository implements TotpCredentialService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcTotpCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TotpCredentialService.Credential> findById(UUID tenantId, UUID id) {
        return jdbc
                .query(
                        "select id,tenant_id,user_id,method,secret_cipher,status,version_no,confirmed_at,disabled_at from iam.mfa_credential where tenant_id=? and id=?",
                        (rs, n) -> map(rs),
                        tenantId,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<TotpCredentialService.Credential> findByUser(UUID tenantId, UUID userId) {
        return jdbc
                .query(
                        "select id,tenant_id,user_id,method,secret_cipher,status,version_no,confirmed_at,disabled_at from iam.mfa_credential where tenant_id=? and user_id=? and method='TOTP' order by updated_at desc limit 1",
                        (rs, n) -> map(rs),
                        tenantId,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public void insert(TotpCredentialService.Credential c, UUID actorId) {
        jdbc.update(
                "insert into iam.mfa_credential(id,tenant_id,user_id,method,secret_cipher,status,version_no,created_by,updated_by) values (?,?,?,?,?,?,?,?,?)",
                c.id(),
                c.tenantId(),
                c.userId(),
                c.method(),
                c.secretCipher(),
                c.status(),
                c.versionNo(),
                actorId,
                actorId);
    }

    @Override
    public int reset(UUID tenantId, UUID id, int expectedVersion, byte[] secretCipher, UUID actorId) {
        return jdbc.update(
                "update iam.mfa_credential set secret_cipher=?,status='PENDING',version_no=version_no+1,confirmed_at=null,disabled_at=null,updated_at=now(),updated_by=? where tenant_id=? and id=? and version_no=? and status in ('PENDING','DISABLED')",
                secretCipher,
                actorId,
                tenantId,
                id,
                expectedVersion);
    }

    @Override
    public int activate(UUID tenantId, UUID id, int expectedVersion, UUID actorId) {
        return jdbc.update(
                "update iam.mfa_credential set status='ACTIVE',version_no=version_no+1,confirmed_at=now(),updated_at=now(),updated_by=? where tenant_id=? and id=? and version_no=? and status='PENDING'",
                actorId,
                tenantId,
                id,
                expectedVersion);
    }

    @Override
    public int disable(UUID tenantId, UUID id, int expectedVersion, UUID actorId) {
        return jdbc.update(
                "update iam.mfa_credential set status='DISABLED',version_no=version_no+1,disabled_at=now(),updated_at=now(),updated_by=? where tenant_id=? and id=? and version_no=? and status='ACTIVE'",
                actorId,
                tenantId,
                id,
                expectedVersion);
    }

    @Override
    public void setAccountMfaLevel(UUID tenantId, UUID userId, short level, UUID actorId) {
        int changed = jdbc.update(
                "update iam.user_account set mfa_level=?,updated_at=now(),updated_by=? where tenant_id=? and id=? and status='ACTIVE'",
                level,
                actorId,
                tenantId,
                userId);
        if (changed != 1) throw new MfaRejectedException("user account MFA level update failed");
    }

    private static TotpCredentialService.Credential map(ResultSet rs) throws SQLException {
        return new TotpCredentialService.Credential(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("method"),
                rs.getBytes("secret_cipher"),
                rs.getString("status"),
                rs.getInt("version_no"),
                instant(rs, "confirmed_at"),
                instant(rs, "disabled_at"));
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        var v = rs.getObject(c, java.time.OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
