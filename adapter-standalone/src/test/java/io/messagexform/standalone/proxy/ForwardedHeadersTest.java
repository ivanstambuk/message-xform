package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test verifying X-Forwarded-* header injection
 * (T-004-31, FR-004-36, S-004-57/58/59).
 *
 * <p>
 * Validates that when {@code proxy.forwarded-headers.enabled} is true
 * (default),
 * the proxy adds {@code X-Forwarded-For}, {@code X-Forwarded-Proto}, and
 * {@code X-Forwarded-Host} to upstream requests. When disabled, no such
 * headers are added.
 *
 * <p>
 * Each test scenario uses its own harness instance with different config
 * since the forwarded-headers setting is per-server.
 */
@DisplayName("X-Forwarded-* header injection — proxy.forwarded-headers.enabled")
class ForwardedHeadersTest {

    // ---------------------------------------------------------------
    // S-004-57 — Default (enabled) → X-Forwarded-* set
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-57: Default config → X-Forwarded-For/Proto/Host sent to backend")
    void defaultEnabled_forwardedHeadersSet() throws Exception {
        ForwardedHeadersHarness harness = new ForwardedHeadersHarness();
        harness.startPassthroughWithForwardedHeaders(true);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + harness.proxyPort + "/api/fwd/default"))
                    .GET()
                    .build();

            HttpResponse<String> response = harness.testClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);

            // Check that backend received X-Forwarded-* headers
            ProxyTestHarness.ReceivedRequest received = harness.receivedRequests.get("/api/fwd/default");
            assertThat(received).isNotNull();

            // X-Forwarded-For: should contain 127.0.0.1 (client loopback)
            List<String> xff = harness.getReceivedHeader(received, "X-Forwarded-For");
            assertThat(xff).isNotEmpty();
            assertThat(xff.getFirst()).contains("127.0.0.1");

            // X-Forwarded-Proto: should be "http"
            List<String> xfp = harness.getReceivedHeader(received, "X-Forwarded-Proto");
            assertThat(xfp).isNotEmpty();
            assertThat(xfp.getFirst()).isEqualTo("http");

            // X-Forwarded-Host: should contain the auto-generated host header
            List<String> xfh = harness.getReceivedHeader(received, "X-Forwarded-Host");
            assertThat(xfh).isNotEmpty();
            assertThat(xfh.getFirst()).contains("127.0.0.1");
        } finally {
            harness.stopInfrastructure();
        }
    }

    // ---------------------------------------------------------------
    // S-004-58 — Disabled → no X-Forwarded-* headers
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-58: Disabled → no X-Forwarded-* headers sent to backend")
    void disabled_noForwardedHeaders() throws Exception {
        ForwardedHeadersHarness harness = new ForwardedHeadersHarness();
        harness.startPassthroughWithForwardedHeaders(false);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + harness.proxyPort + "/api/fwd/disabled"))
                    .GET()
                    .build();

            HttpResponse<String> response = harness.testClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);

            ProxyTestHarness.ReceivedRequest received = harness.receivedRequests.get("/api/fwd/disabled");
            assertThat(received).isNotNull();

            // No X-Forwarded-* headers should be present
            List<String> xff = harness.getReceivedHeader(received, "X-Forwarded-For");
            assertThat(xff).isEmpty();

            List<String> xfp = harness.getReceivedHeader(received, "X-Forwarded-Proto");
            assertThat(xfp).isEmpty();

            List<String> xfh = harness.getReceivedHeader(received, "X-Forwarded-Host");
            assertThat(xfh).isEmpty();
        } finally {
            harness.stopInfrastructure();
        }
    }

    // ---------------------------------------------------------------
    // S-004-59 — Existing X-Forwarded-For → client IP appended
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-59: Existing X-Forwarded-For → client IP appended")
    void existingXff_clientIpAppended() throws Exception {
        ForwardedHeadersHarness harness = new ForwardedHeadersHarness();
        harness.startPassthroughWithForwardedHeaders(true);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + harness.proxyPort + "/api/fwd/append"))
                    .header("X-Forwarded-For", "1.1.1.1")
                    .GET()
                    .build();

            HttpResponse<String> response = harness.testClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);

            ProxyTestHarness.ReceivedRequest received = harness.receivedRequests.get("/api/fwd/append");
            assertThat(received).isNotNull();

            // X-Forwarded-For should have both the original and the client IP
            List<String> xff = harness.getReceivedHeader(received, "X-Forwarded-For");
            assertThat(xff).isNotEmpty();
            assertThat(xff.getFirst()).contains("1.1.1.1");
            assertThat(xff.getFirst()).contains("127.0.0.1");
        } finally {
            harness.stopInfrastructure();
        }
    }

    // ---------------------------------------------------------------
    // Inner harness for forwarded headers config
    // ---------------------------------------------------------------

    /**
     * Extends ProxyTestHarness with a method to start with forwarded headers
     * enabled or disabled.
     */
    private static class ForwardedHeadersHarness extends ProxyTestHarness {
        void startPassthroughWithForwardedHeaders(boolean enabled) throws Exception {
            startWithForwardedHeaders(enabled);
        }
    }
}
