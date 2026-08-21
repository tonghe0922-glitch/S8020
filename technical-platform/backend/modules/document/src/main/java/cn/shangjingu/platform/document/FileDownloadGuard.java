package cn.shangjingu.platform.document;

/**
 * Explicit authorization boundary for file download.
 * C7 wires this to permissions/Step-Up/audit; C3 never treats authentication alone as authorization.
 */
@FunctionalInterface
public interface FileDownloadGuard {
    void requireAllowed(FileObjectService.FileObject file);
}
