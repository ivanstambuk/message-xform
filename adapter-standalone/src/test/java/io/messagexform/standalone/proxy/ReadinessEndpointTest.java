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
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the readiness endpoint (T-004-34, FR-004-22,
 * S-004-35/36/37).
 *
 * <p>
 * Architecture:
 *
 * <pre>
 *   TestClient ← HTTP → Javalin (ReadinessHandler + ProxyHandler) ← TCP check → Mock Backend
 * </pre>
 *
 * <p>
 * Each test manages its own Javalin+backend lifecycle to control engine
 * loaded state and backend reachability independently.
 */
@DisplayName("Readiness endpoint — T-004-34")
class ReadinessEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer mockBackend;
    private int backendPort;
    private Javalin app;
    private int proxyPort;
    private final HttpClient testClient =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @AfterEach
    void stopInfrastructure() {
        if (app != null) {
            app.stop();
        }
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    // ---------------------------------------------------------------
    // S-004-35 — Readiness READY: engine loaded + backend reachable
    // ---------------------------------------------------------------

    @Test
    @DisplayName(
            "S-004-35: GET /ready → 200 {\"status\": \"READY\", \"engine\": \"loaded\", \"backend\": \"reachable\"}")
    void ready_engineLoadedBackendReachable() throws Exception {
        startWithBackend(true, true);

        HttpResponse<String> response = getReady();

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("READY");
        assertThat(body.get("engine").asText()).isEqualTo("loaded");
        assertThat(body.get("backend").asText()).isEqualTo("reachable");
    }

    @Test
    @DisplayName("S-004-35: GET /ready returns application/json content type")
    void ready_contentTypeJson() throws Exception {
        startWithBackend(true, true);

        HttpResponse<String> response = getReady();

        assertThat(response.headers().firstValue("content-type")).isPresent().hasValueSatisfying(ct -> assertThat(ct)
                .contains("application/json"));
    }

    // ---------------------------------------------------------------
    // S-004-36 — Readiness NOT_READY: before engine loads
    // ---------------------------------------------------------------

    @Test
    @DisplayName("S-004-36: GET /ready before engine loads → 503 {\"status\": \"NOT_READY\"}")
    void ready_engineNotLoaded() throws Exception {
        startWithBackend(false, true);

        HttpResponse<String> response = getReady();

        assertThat(response.statusCode()).isEqualTo(503);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("NOT_READY");
    }

    // ---------------------------------------------------------------
    // S-004-37 — Readiness NOT_READY: backend unreachable
    // ---------------------------------------------------------------

    @Test
    @DisplayName(
            "S-004-37: GET /ready with backend unreachable → 503 {\"status\": \"NOT_READY\", \"reason\": \"backend_unreachable\"}")
    void ready_backendUnreachable() throws Exception {
        startWithBackend(true, false);

        HttpResponse<String> response = getReady();

        assertThat(response.statusCode()).isEqualTo(503);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("NOT_READY");
        assertThat(body.get("reason").asText()).isEqualTo("backend_unreachable");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Starts the Javalin server with a ReadinessHandler configured for the
     * given engine-loaded and backend-available states.
     *
     * @param engineLoaded   if true, engine has loaded specs (normal state)
     * @param backendRunning if true, start a real mock backend; if false,
     *                       use a port with nothing listening
     */
    private void startWithBackend(boolean engineLoaded, boolean backendRunning) throws IOException {
        if (backendRunning) {
            mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backendPort = mockBackend.getAddress().getPort();
            mockBackend.createContext("/", exchange -> {
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            mockBackend.start();
        } else {
            // Allocate an ephemeral port then close immediately —
            // ServerSocket releases the port more reliably than HttpServer.
            try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                backendPort = ss.getLocalPort();
            }
        }

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .backendConnectTimeoutMs(500)
                .healthEnabled(true)
                .readyPath("/ready")
                .build();

        EngineRegistry registry = new EngineRegistry();
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);
        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(engine, adapter, upstreamClient, -1, true);

        // The ReadinessHandler needs to check:
        // 1. Engine loaded → engine.isLoaded()
        // 2. Backend reachable → TCP connect to backend host:port
        ReadinessHandler readinessHandler = new ReadinessHandler(
                engineLoaded ? () -> true : () -> false,
                config.backendHost(),
                config.backendPort(),
                config.backendConnectTimeoutMs());

        app = Javalin.create()
                .addHttpHandler(HandlerType.GET, config.readyPath(), readinessHandler)
                .addHttpHandler(HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(HandlerType.POST, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();
    }

    private HttpResponse<String> getReady() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/ready"))
                .GET()
                .build();
        return testClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
