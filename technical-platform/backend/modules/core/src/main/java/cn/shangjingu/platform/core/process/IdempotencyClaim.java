package cn.shangjingu.platform.core.process;

import java.util.UUID;

public record IdempotencyClaim(UUID resourceId, boolean existing) {
}
