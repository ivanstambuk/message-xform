package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the health endpoint (T-004-33, FR-004-21, S-004-34).
 *
 * <p>
 * Architecture:
 *
 * <pre>
 *   TestClient ← HTTP → Javalin (HealthHandler + ProxyHandler) ← HTTP → Mock Backend
 * </pre>
 *
 * <p>
 * Verifies that {@code GET /health} returns {@code 200 {"status": "UP"}}
 * and that the health endpoint uses the correct content type.
 */
@DisplayName("Health endpoint — T-004-33")
class HealthEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer mockBackend;
    private static int backendPort;
    private static Javalin app;
    private static int proxyPort;
    private static HttpClient testClient;

    @BeforeAll
    static void startInfrastructure() throws IOException {
        // --- Mock Backend ---
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mockBackend.start();

        // --- Proxy config ---
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .healthEnabled(true)
                .healthPath("/health")
                .build();

        // --- Engine (empty registry → passthrough) ---
        EngineRegistry registry = new EngineRegistry();
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);
        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(engine, adapter, upstreamClient, -1, true);

        // Register health handler BEFORE the proxy wildcard
        HealthHandler healthHandler = new HealthHandler();

        app = Javalin.create()
                .addHttpHandler(HandlerType.GET, config.healthPath(), healthHandler)
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
    // S-004-34 — Health check UP: GET /health → 200 {"status": "UP"}
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-34: GET /health → 200 {\"status\": \"UP\"}")
    void health_returnsUp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("S-004-34: GET /health returns application/json content type")
    void health_contentTypeJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/json"));
    }

    @Test
    @DisplayName("POST /health is NOT handled by health handler (only GET)")
    void health_postNotHandled() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/health"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = testClient.send(request, HttpResponse.BodyHandlers.ofString());

        // POST /health should be handled by the proxy wildcard, not the health
        // handler — so it should proxy to the backend and get the backend's echo
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"ok\"");
    }
}
