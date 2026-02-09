package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ProxyConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: admin/health endpoints take precedence over profile
 * matching (T-004-35, S-004-38, S-004-70).
 *
 * <p>
 * Architecture:
 *
 * <pre>
 *   TestClient ← HTTP → Javalin (Health/Ready handlers + ProxyHandler) ← HTTP → Mock Backend
 * </pre>
 *
 * <p>
 * A wildcard transform profile ({@code path: /*}) is loaded so the engine
 * matches every path. Despite this, {@code GET /health} and
 * {@code GET /ready} must still return their health/readiness responses,
 * NOT transformed proxy responses.
 */
@DisplayName("Endpoint priority — T-004-35")
class EndpointPriorityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer mockBackend;
    private static int backendPort;
    private static Javalin app;
    private static int proxyPort;
    private static HttpClient testClient;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        // --- Mock Backend ---
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            String responseBody = "{\"source\":\"backend\",\"path\":\""
                    + exchange.getRequestURI().getPath() + "\"}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        mockBackend.start();

        // --- Config ---
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .backendConnectTimeoutMs(2000)
                .healthEnabled(true)
                .healthPath("/health")
                .readyPath("/ready")
                .build();

        // --- Engine with a wildcard profile that matches EVERYTHING ---
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        // Load identity spec + wildcard profile
        Path specPath = Path.of(EndpointPriorityTest.class
                .getClassLoader()
                .getResource("test-specs/identity-transform.yaml")
                .getPath());
        engine.loadSpec(specPath);

        Path profilePath = Path.of(EndpointPriorityTest.class
                .getClassLoader()
                .getResource("test-profiles/wildcard-profile.yaml")
                .getPath());
        engine.loadProfile(profilePath);

        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(engine, adapter, upstreamClient, -1, true);

        // Health + readiness handlers
        HealthHandler healthHandler = new HealthHandler();
        ReadinessHandler readinessHandler = new ReadinessHandler(
                () -> true, config.backendHost(), config.backendPort(), config.backendConnectTimeoutMs());

        // Register health/readiness BEFORE proxy wildcard
        app = Javalin.create()
                .addHttpHandler(HandlerType.GET, config.healthPath(), healthHandler)
                .addHttpHandler(HandlerType.GET, config.readyPath(), readinessHandler)
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.POST, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.PUT, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.DELETE, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.PATCH, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.HEAD, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.OPTIONS, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();
        testClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (app != null) {
            app.stop();
        }
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    // ---------------------------------------------------------------
    // S-004-38 — Health/readiness not subject to transforms
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-38: GET /health returns health response, NOT transformed/proxied")
    void healthEndpoint_notTransformed() throws Exception {
        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("UP");
        // Must NOT contain backend fields — health was served directly
        assertThat(body.has("source")).isFalse();
    }

    @Test
    @DisplayName("S-004-38: GET /ready returns readiness response, NOT transformed/proxied")
    void readinessEndpoint_notTransformed() throws Exception {
        HttpResponse<String> response = get("/ready");

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("READY");
        // Must NOT contain backend fields
        assertThat(body.has("source")).isFalse();
    }

    // ---------------------------------------------------------------
    // S-004-70 — Wildcard profile vs admin/health
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-70: Wildcard path=/* profile exists → GET /health still returns health response")
    void wildcardProfile_healthStillWins() throws Exception {
        // Even though a profile with path=/* is loaded (and would match GET /health),
        // the health endpoint must take precedence
        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("S-004-70: Wildcard path=/* profile exists → GET /ready still returns readiness response")
    void wildcardProfile_readinessStillWins() throws Exception {
        HttpResponse<String> response = get("/ready");

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("S-004-70: Non-health path with wildcard profile → proxied normally")
    void wildcardProfile_nonHealthPath_proxiedNormally() throws Exception {
        // /api/test should be matched by the wildcard profile and proxied
        HttpResponse<String> response = get("/api/test");

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        // Should come from the backend
        assertThat(body.get("source").asText()).isEqualTo("backend");
        assertThat(body.get("path").asText()).isEqualTo("/api/test");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + path))
                .GET()
                .build();
        return testClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
