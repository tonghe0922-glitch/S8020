package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.document.FileObjectService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import java.util.Optional;

/**
 * Platform SPI for resolving authoritative business ownership/scope of an attached file.
 * PHASE-06 supplies a fail-closed default; later business modules may replace it using their approved source tables.
 */
@FunctionalInterface
public interface FileDownloadAuthorizationTargetResolver {
    Optional<AuthorizationTarget> resolve(FileObjectService.FileObject file);
}
