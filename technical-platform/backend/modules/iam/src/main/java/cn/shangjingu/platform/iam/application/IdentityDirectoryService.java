package cn.shangjingu.platform.iam.application;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.iam.domain.AuthorizationSnapshot;
import cn.shangjingu.platform.iam.domain.IdentityDirectoryPort;
import cn.shangjingu.platform.iam.domain.IdentityRecord;
import cn.shangjingu.platform.iam.domain.UserAccountRecord;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.org.application.OrgDirectoryService;
import cn.shangjingu.platform.org.domain.AppointmentRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class IdentityDirectoryService {
    private final IdentityDirectoryPort identities;
    private final OrgDirectoryService organizations;
    private final TenantTransactionRunner tenantTransactions;

    public IdentityDirectoryService(
            IdentityDirectoryPort identities,
            OrgDirectoryService organizations,
            TenantTransactionRunner tenantTransactions) {
        this.identities = identities;
        this.organizations = organizations;
        this.tenantTransactions = tenantTransactions;
    }

    public Optional<UUID> resolveTenant(String tenantCode) {
        return identities.findTenantIdByCode(tenantCode);
    }

    public Optional<UserAccountRecord> findAccount(UUID tenantId, String loginName) {
        return tenantTransactions.required(tenantId, () -> identities.findAccount(tenantId, loginName));
    }

    public Optional<UserAccountRecord> findAccountById(UUID tenantId, UUID userId) {
        return tenantTransactions.required(tenantId, userId, null, () -> identities.findAccountById(tenantId, userId));
    }

    public List<IdentityRecord> activeIdentities(UUID tenantId, UUID userId) {
        return tenantTransactions.required(
                tenantId,
                userId,
                null,
                () -> identities.findActiveIdentities(tenantId, userId).stream()
                        .filter(identity -> identity.employeeId() != null)
                        .filter(identity -> identity.orgId() != null)
                        .filter(identity -> identity.positionId() != null)
                        .toList());
    }

    public Optional<IdentityRecord> activeIdentity(UUID tenantId, UUID userId, UUID identityId) {
        return tenantTransactions.required(tenantId, userId, identityId, () ->
                identities.findActiveIdentity(tenantId, userId, identityId)
                        .filter(identity -> identity.employeeId() != null && identity.orgId() != null && identity.positionId() != null)
                        .filter(identity -> organizations.hasActiveAppointment(
                                tenantId, identity.employeeId(), identity.orgId(), identity.positionId())));
    }

    public Optional<AppointmentRecord> activeAppointment(UUID tenantId, IdentityRecord identity) {
        return tenantTransactions.required(
                tenantId,
                identity.userId(),
                identity.id(),
                () -> matchingAppointments(tenantId, identity).stream().findFirst());
    }

    public Optional<AppointmentRecord> activeAppointment(UUID tenantId, UUID userId, UUID identityId) {
        return tenantTransactions.required(tenantId, userId, identityId, () ->
                identities.findActiveIdentity(tenantId, userId, identityId)
                        .flatMap(identity -> matchingAppointments(tenantId, identity).stream().findFirst()));
    }

    public Optional<AppointmentRecord> activeAppointment(
            UUID tenantId,
            UUID userId,
            UUID identityId,
            UUID appointmentId) {
        return tenantTransactions.required(tenantId, userId, identityId, () ->
                identities.findActiveIdentity(tenantId, userId, identityId)
                        .flatMap(identity -> matchingAppointments(tenantId, identity).stream()
                                .filter(appointment -> appointment.id().equals(appointmentId))
                                .findFirst()));
    }

    public AuthorizationSnapshot authorization(UUID tenantId, UUID userId, UUID identityId) {
        return tenantTransactions.required(tenantId, userId, identityId, () ->
                AuthorizationSnapshot.from(identities.findAuthorizationGrants(tenantId, userId, identityId)));
    }

    public AuthorizationSnapshot authorization(SessionContext subject) {
        DatabaseSecurityContext databaseContext = new DatabaseSecurityContext(
                subject.tenantId(),
                subject.userId(),
                subject.identityId(),
                subject.employeeId(),
                subject.appointmentId(),
                subject.orgId(),
                subject.positionId());
        return tenantTransactions.required(databaseContext, () ->
                AuthorizationSnapshot.from(identities.findAuthorizationGrants(
                        subject.tenantId(), subject.userId(), subject.identityId())));
    }

    public void markLogin(UUID tenantId, UUID userId) {
        tenantTransactions.required(tenantId, userId, null, () -> {
            identities.updateLastLogin(tenantId, userId);
            return null;
        });
    }

    private List<AppointmentRecord> matchingAppointments(UUID tenantId, IdentityRecord identity) {
        if (identity.employeeId() == null || identity.orgId() == null || identity.positionId() == null) {
            return List.of();
        }
        return organizations.activeAppointments(tenantId, identity.employeeId()).stream()
                .filter(appointment -> appointment.orgId().equals(identity.orgId()))
                .filter(appointment -> appointment.positionId().equals(identity.positionId()))
                .toList();
    }
}
