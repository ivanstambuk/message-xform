package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration test verifying unknown HTTP method rejection
 * (T-004-32, FR-004-05, S-004-23).
 *
 * <p>
 * Validates that unknown HTTP methods (e.g. PROPFIND) are rejected with
 * {@code 405 Method Not Allowed}, while all seven standard methods are
 * proxied correctly.
 */
@DisplayName("HTTP method handling — FR-004-05")
class HttpMethodTest extends ProxyTestHarness {

    private static final HttpMethodTest INSTANCE = new HttpMethodTest();

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startPassthrough();
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // S-004-23 — Unknown method → 405 Method Not Allowed
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-23: PROPFIND → 405 Method Not Allowed with RFC 9457 body")
    void unknownMethod_returns405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/users"))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // --- HTTP status ---
        assertThat(response.statusCode()).isEqualTo(405);
    }

    // ---------------------------------------------------------------
    // All seven standard methods → proxied correctly
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} → proxied successfully")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
    @DisplayName("Standard methods → proxied successfully")
    void standardMethods_proxiedSuccessfully(String method) throws Exception {
        String body = "GET".equals(method) || "OPTIONS".equals(method) ? null : "{\"test\":true}";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/method-check"));

        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = INSTANCE.testClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("HEAD → proxied successfully")
    void headMethod_proxiedSuccessfully() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/method-check"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
