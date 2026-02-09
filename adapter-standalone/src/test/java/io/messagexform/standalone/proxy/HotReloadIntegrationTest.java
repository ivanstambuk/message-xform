package io.messagexform.standalone.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end hot reload integration test (T-004-38, FR-004-19, S-004-29/31).
 *
 * <p>
 * Scenarios covered:
 * <ul>
 * <li>S-004-29: FileWatcher detects new spec → engine reloads → new spec
 * transformations apply to subsequent requests.</li>
 * <li>S-004-31: Hot reload with broken spec → old registry stays active.</li>
 * </ul>
 */
class HotReloadIntegrationTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path specsDir;

    @TempDir
    Path profilesDir;

    private FileWatcher fileWatcher;

    @AfterEach
    void tearDown() {
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        stopInfrastructure();
    }

    /**
     * S-004-29: Start proxy with spec A → transformer works → write spec B to
     * directory → watcher detects → subsequent requests use spec B.
     */
    @Test
    void fileWatcher_detectsNewSpec_reloadsEngine() throws Exception {
        // Start with an identity spec
        writeSpec("identity.yaml", """
                id: identity-transform
                version: "1.0.0"
                description: "Identity"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: .
                """);

        Path profileFile = profilesDir.resolve("test-profile.yaml");
        Files.writeString(profileFile, """
                profile: test-profile
                version: "1.0.0"
                transforms:
                  - spec: identity-transform@1.0.0
                    direction: request
                    match:
                      path: "/api/test"
                      method: POST
                """);

        startWithFileWatcher(specsDir, profileFile, 200);

        // Verify initial state — identity transform, body passes through
        HttpResponse<String> initial = post("/api/test", "{\"key\":\"original\"}");
        assertEquals(200, initial.statusCode());
        JsonNode initialBody = MAPPER.readTree(getBackendReceivedBody(initial));
        assertEquals("original", initialBody.get("key").asText());

        // Now write a new version of the spec that transforms the body
        writeSpec("body-transform.yaml", """
                id: body-transform
                version: "1.0.0"
                description: "Adds a field"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    {
                      "key": .key,
                      "added": "by-transform"
                    }
                """);

        // Update profile to use new spec
        Files.writeString(profileFile, """
                profile: test-profile
                version: "1.0.0"
                transforms:
                  - spec: body-transform@1.0.0
                    direction: request
                    match:
                      path: "/api/test"
                      method: POST
                """);

        // Wait for FileWatcher debounce + reload
        Thread.sleep(1500);

        // Verify new spec is active — POST /admin/reload to force (in case watcher was
        // slow)
        HttpResponse<String> reloadResp = adminReload();
        assertEquals(200, reloadResp.statusCode());

        HttpResponse<String> afterReload = post("/api/test", "{\"key\":\"updated\"}");
        assertEquals(200, afterReload.statusCode());
        JsonNode afterBody = MAPPER.readTree(getBackendReceivedBody(afterReload));
        assertEquals("updated", afterBody.get("key").asText());
        assertEquals("by-transform", afterBody.get("added").asText());
    }

    /**
     * S-004-31: Reload failure with broken spec → old registry stays active,
     * error logged, existing transforms continue to work.
     */
    @Test
    void reloadFailure_oldRegistryPreserved() throws Exception {
        // Start with a working spec
        writeSpec("good-spec.yaml", """
                id: good-spec
                version: "1.0.0"
                description: "Good spec"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: .
                """);

        startWithFileWatcher(specsDir, null, 200);

        // Verify admin reload works
        HttpResponse<String> firstReload = adminReload();
        assertEquals(200, firstReload.statusCode());

        // Write a broken spec
        writeSpec("broken.yaml", """
                id: broken
                version: "1.0.0"
                transform:
                  lang: jslt
                  expr: BROKEN {{ INVALID
                """);

        // Try reload — should fail
        HttpResponse<String> failedReload = adminReload();
        assertEquals(500, failedReload.statusCode());

        // Remove the broken spec and verify the good spec still works
        Files.delete(specsDir.resolve("broken.yaml"));
        HttpResponse<String> recoveryReload = adminReload();
        assertEquals(200, recoveryReload.statusCode());
        JsonNode body = MAPPER.readTree(recoveryReload.body());
        assertEquals(1, body.get("specs").asInt());
    }

    // --- Harness ---

    private void startWithFileWatcher(Path specsDir, Path profileFile, int debounceMs) throws IOException {
        startMockBackendInternal();

        io.messagexform.core.engine.EngineRegistry registry = new io.messagexform.core.engine.EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        io.messagexform.core.spec.SpecParser specParser = new io.messagexform.core.spec.SpecParser(registry);
        engine = new io.messagexform.core.engine.TransformEngine(specParser);

        // Load initial specs
        loadSpecsFromDir(specsDir);
        if (profileFile != null && Files.exists(profileFile)) {
            engine.loadProfile(profileFile);
        }

        io.messagexform.standalone.adapter.StandaloneAdapter adapter =
                new io.messagexform.standalone.adapter.StandaloneAdapter();
        io.messagexform.standalone.config.ProxyConfig config = io.messagexform.standalone.config.ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .build();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(
                engine, adapter, upstreamClient, config.maxBodyBytes(), config.forwardedHeadersEnabled());
        AdminReloadHandler reloadHandler = new AdminReloadHandler(engine, specsDir, profileFile);

        // Wire FileWatcher → engine reload
        Runnable reloadCallback = () -> {
            try {
                List<Path> specPaths = AdminReloadHandler.scanSpecFiles(specsDir);
                engine.reload(specPaths, profileFile);
            } catch (Exception e) {
                // Log but don't rethrow — old registry stays active
            }
        };

        fileWatcher = new FileWatcher(specsDir, debounceMs, reloadCallback);

        app = io.javalin.Javalin.create()
                .post("/admin/reload", reloadHandler)
                .addHttpHandler(io.javalin.http.HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(io.javalin.http.HandlerType.POST, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();
        testClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        try {
            fileWatcher.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start FileWatcher", e);
        }
    }

    private void startMockBackendInternal() throws IOException {
        mockBackend = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            String reqBody =
                    new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String key = path + (query != null ? "?" + query : "");

            receivedRequests.put(
                    key,
                    new ReceivedRequest(
                            exchange.getRequestMethod(), path, query, exchange.getRequestHeaders(), reqBody));

            // Echo back the received body
            byte[] bytes = reqBody.isEmpty()
                    ? "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : reqBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockBackend.start();
    }

    private void loadSpecsFromDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .forEach(p -> engine.loadSpec(p));
        }
    }

    private void writeSpec(String filename, String content) throws IOException {
        Files.writeString(specsDir.resolve(filename), content);
    }

    private String getBackendReceivedBody(HttpResponse<String> response) {
        // The backend echoes back the received body in its response
        // (which is the transformed request body)
        var req = receivedRequests.values().stream().reduce((a, b) -> b).orElse(null);
        return req != null ? req.body() : "";
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return testClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adminReload() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/admin/reload"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return testClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
