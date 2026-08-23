package cn.shangjingu.platform.org.application;

import java.util.UUID;

public interface OrgCodeAllocator {
    String allocate(UUID tenantId);
}
