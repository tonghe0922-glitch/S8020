package cn.shangjingu.platform.integration;

import java.net.http.HttpRequest;

/** Optional endpoint auth strategy. The platform has no default secret or fixed-success authentication. */
public interface IntegrationAuthProvider {
    String authType();
    void apply(HttpRequest.Builder request, IntegrationHttpClient.Endpoint endpoint);
}
