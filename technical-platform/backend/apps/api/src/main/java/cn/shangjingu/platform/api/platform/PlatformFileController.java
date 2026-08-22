package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.document.FileObjectService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/files")
@ConditionalOnBean(FileObjectService.class)
public class PlatformFileController {
    private final FileObjectService files;
    private final TenantTransactionRunner transactions;

    public PlatformFileController(FileObjectService files, TenantTransactionRunner transactions) {
        this.files = files;
        this.transactions = transactions;
    }

    @GetMapping("/{fileId}/download")
    public DownloadResponse download(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID fileId,
            @RequestHeader(value = "X-Step-Up-Ticket", required = false) String stepUpTicket,
            @RequestParam(defaultValue = "60") long ttlSeconds) {
        if (principal == null)
            throw new org.springframework.security.access.AccessDeniedException("authenticated session is required");
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        try (FileDownloadAuthorizationContext.Scope ignored =
                FileDownloadAuthorizationContext.open(principal.context(), stepUpTicket)) {
            String url = transactions.required(
                    principal.context().tenantId(),
                    principal.context().userId(),
                    principal.context().identityId(),
                    () -> files.presignDownload(principal.context().tenantId(), fileId, ttl));
            return new DownloadResponse(url, ttlSeconds);
        }
    }

    public record DownloadResponse(String url, long expiresInSeconds) {}
}
