package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test verifying response body size enforcement
 * (T-004-30, FR-004-13, S-004-56).
 *
 * <p>
 * Validates that backend responses exceeding {@code proxy.max-body-bytes} are
 * rejected with {@code 502 Bad Gateway} and an RFC 9457 Problem Details body,
 * while responses within the limit are forwarded to the client.
 *
 * <p>
 * Uses {@link ProxyTestHarness#registerBackendHandler} to force the mock
 * backend to return specific-sized responses, independent of request size.
 */
@DisplayName("Response body size enforcement — proxy.max-body-bytes")
class ResponseBodySizeTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResponseBodySizeTest INSTANCE = new ResponseBodySizeTest();

    /** Set limit to 1 KB for easy testing. */
    private static final int MAX_BODY_BYTES = 1024;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        INSTANCE.startPassthroughWithMaxBodyBytes(MAX_BODY_BYTES);

        // Register a handler that returns a small response (within limit)
        String smallResponse = "{\"status\":\"ok\",\"data\":\"" + "x".repeat(100) + "\"}";
        INSTANCE.registerBackendHandler("/api/resp/small", 200, "application/json", smallResponse);

        // Register a handler that returns a large response (exceeding limit)
        String largeResponse = "{\"status\":\"ok\",\"data\":\"" + "x".repeat(MAX_BODY_BYTES + 500) + "\"}";
        INSTANCE.registerBackendHandler("/api/resp/large", 200, "application/json", largeResponse);
    }

    @AfterAll
    static void stopAll() {
        INSTANCE.stopInfrastructure();
    }

    // ---------------------------------------------------------------
    // Backend response within limit → accepted and forwarded
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Backend response within limit → 200 OK (forwarded to client)")
    void responseWithinLimit_isForwarded() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/resp/small"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"");
    }

    // ---------------------------------------------------------------
    // S-004-56 — Backend response exceeding limit → 502 Bad Gateway
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Backend response exceeding limit → 502 with RFC 9457 body")
    void responseExceedingLimit_returns502() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + INSTANCE.proxyPort + "/api/resp/large"))
                .GET()
                .build();

        HttpResponse<String> response = INSTANCE.testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // --- HTTP status ---
        assertThat(response.statusCode()).isEqualTo(502);

        // --- Content-Type ---
        assertThat(response.headers().firstValue("content-type"))
                .isPresent()
                .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

        // --- RFC 9457 body structure ---
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("type").asText()).isEqualTo("urn:message-xform:proxy:body-too-large");
        assertThat(body.get("title").asText()).isEqualTo("Payload Too Large");
        assertThat(body.get("status").asInt()).isEqualTo(502);
        assertThat(body.has("detail")).isTrue();
        assertThat(body.get("instance").asText()).isEqualTo("/api/resp/large");
    }
}
