package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.integration.WebhookIngressService;
import cn.shangjingu.platform.integration.WebhookSignatureVerifier;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/webhooks")
public class PlatformWebhookController {
    private final TenantTransactionRunner transactions;
    private final WebhookIngressService webhooks;
    private final WebhookSignatureVerifier verifier;

    public PlatformWebhookController(
            TenantTransactionRunner transactions, WebhookIngressService webhooks, WebhookSignatureVerifier verifier) {
        this.transactions = transactions;
        this.webhooks = webhooks;
        this.verifier = verifier;
    }

    @PostMapping("/{tenantId}/{endpointCode}")
    public ResponseEntity<WebhookResponse> receive(
            @PathVariable UUID tenantId,
            @PathVariable String endpointCode,
            @RequestHeader("X-Provider-Event-Id") String providerEventId,
            @RequestHeader("X-Provider-Event-Type") String providerEventType,
            @RequestHeader(value = "X-Provider-Signature", required = false) String signature,
            @RequestBody String payload) {
        WebhookIngressService.WebhookResult result = transactions.required(
                tenantId,
                () -> webhooks.receive(
                        tenantId,
                        null,
                        new WebhookIngressService.ReceiveCommand(
                                endpointCode, providerEventId, providerEventType, payload, signature),
                        verifier));
        WebhookResponse body =
                new WebhookResponse(result.id(), result.duplicate(), result.signatureValid(), result.status());
        if (!result.signatureValid())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        return result.duplicate()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    public record WebhookResponse(UUID evidenceId, boolean duplicate, boolean signatureValid, String status) {}
}
