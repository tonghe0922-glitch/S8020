package cn.shangjingu.platform.integration;

/** Mandatory provider-specific signature verifier; the platform supplies no allow-all default. */
@FunctionalInterface
public interface WebhookSignatureVerifier {
    boolean verify(WebhookRequest request);

    record WebhookRequest(
            String endpointCode, String providerEventId, String eventType, String payload, String signature) {}
}
