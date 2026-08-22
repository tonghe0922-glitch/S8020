package cn.shangjingu.platform.iam.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityDirectoryPort {
    Optional<UUID> findTenantIdByCode(String tenantCode);

    Optional<UserAccountRecord> findAccount(UUID tenantId, String loginName);

    Optional<UserAccountRecord> findAccountById(UUID tenantId, UUID userId);

    List<IdentityRecord> findActiveIdentities(UUID tenantId, UUID userId);

    Optional<IdentityRecord> findActiveIdentity(UUID tenantId, UUID userId, UUID identityId);

    List<AuthorizationGrant> findAuthorizationGrants(UUID tenantId, UUID userId, UUID identityId);

    void updateLastLogin(UUID tenantId, UUID userId);
}
