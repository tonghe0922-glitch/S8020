package cn.shangjingu.platform.org.domain;

import java.util.UUID;

public record OrganizationUnit(
        UUID id,
        UUID tenantId,
        String orgCode,
        String orgName,
        String orgType,
        UUID parentId,
        String path,
        String status) {
}
