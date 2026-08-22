package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.domain.IdentityRecord;
import cn.shangjingu.platform.iam.session.SessionContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SessionViewResponse(
        UUID tenantId,
        UUID userId,
        UUID identityId,
        UUID employeeId,
        UUID appointmentId,
        UUID orgId,
        UUID positionId,
        List<String> permissions,
        List<AvailableIdentityView> availableIdentities) {

    public static SessionViewResponse from(
            SessionContext context, List<String> permissions, List<IdentityRecord> identities) {
        return new SessionViewResponse(
                context.tenantId(),
                context.userId(),
                context.identityId(),
                context.employeeId(),
                context.appointmentId(),
                context.orgId(),
                context.positionId(),
                List.copyOf(permissions),
                identities.stream().map(AvailableIdentityView::from).toList());
    }

    public record AvailableIdentityView(
            UUID identityId,
            String identityType,
            String identityName,
            UUID orgId,
            UUID positionId,
            boolean primary,
            OffsetDateTime effectiveStartAt,
            OffsetDateTime effectiveEndAt) {

        static AvailableIdentityView from(IdentityRecord identity) {
            return new AvailableIdentityView(
                    identity.id(),
                    identity.identityType(),
                    identity.identityName(),
                    identity.orgId(),
                    identity.positionId(),
                    identity.primary(),
                    identity.effectiveStartAt(),
                    identity.effectiveEndAt());
        }
    }
}
