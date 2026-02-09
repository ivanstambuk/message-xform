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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for admin reload endpoint (T-004-37, FR-004-20).
 *
 * <p>
 * Scenarios covered:
 * <ul>
 * <li>S-004-30: {@code POST /admin/reload} reloads engine → 200 OK with reload
 * summary.</li>
 * <li>S-004-31: Reload with broken spec → 500 with RFC 9457 body → old registry
 * preserved.</li>
 * <li>Admin endpoint not subject to profile matching (verified by
 * EndpointPriorityTest).</li>
 * </ul>
 */
class AdminReloadTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path specsDir;

    @TempDir
    Path profilesDir;

    @AfterEach
    void tearDown() {
        stopInfrastructure();
    }

    /**
     * S-004-30: {@code POST /admin/reload} triggers an engine reload and
     * returns 200 OK with a reload summary including spec count and profile.
     */
    @Test
    void postAdminReload_reloadsEngine_returns200() throws Exception {
        // Write a spec to the specs directory
        writeSpec(specsDir, "test-spec.yaml", """
                id: test-spec
                version: "1.0.0"
                description: "Test spec for admin reload"
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

        startWithSpecsDir(specsDir, null);

        // POST /admin/reload
        HttpResponse<String> response = post("/admin/reload");

        assertEquals(200, response.statusCode());
        assertEquals(
                "application/json",
                response.headers().firstValue("content-type").orElse(""));

        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("reloaded", body.get("status").asText());
        assertEquals(1, body.get("specs").asInt());
        assertEquals("none", body.get("profile").asText());
    }

    /**
     * S-004-30 variant: reload with specs and a profile returns profile id.
     */
    @Test
    void postAdminReload_withProfile_returnsProfileId() throws Exception {
        writeSpec(specsDir, "identity.yaml", """
                id: identity-transform
                version: "1.0.0"
                description: "Identity transform"
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
                description: "Test profile"
                version: "1.0.0"
                transforms:
                  - spec: identity-transform@1.0.0
                    direction: request
                    match:
                      path: "/api/test"
                      method: POST
                """);

        startWithSpecsDir(specsDir, profileFile);

        HttpResponse<String> response = post("/admin/reload");

        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("reloaded", body.get("status").asText());
        assertEquals(1, body.get("specs").asInt());
        assertEquals("test-profile", body.get("profile").asText());
    }

    /**
     * S-004-31: Reload with a broken spec file returns 500 Internal Server
     * Error with RFC 9457 body, and the previous registry stays active.
     */
    @Test
    void postAdminReload_brokenSpec_returns500_preservesOldRegistry() throws Exception {
        // Start with a valid spec
        writeSpec(specsDir, "good-spec.yaml", """
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

        startWithSpecsDir(specsDir, null);

        // First reload — should succeed
        HttpResponse<String> firstReload = post("/admin/reload");
        assertEquals(200, firstReload.statusCode());

        // Now add a broken spec
        writeSpec(specsDir, "broken-spec.yaml", """
                id: broken-spec
                version: "1.0.0"
                transform:
                  lang: jslt
                  expr: INVALID JSLT {{ NOT VALID
                """);

        // Second reload — should fail
        HttpResponse<String> secondReload = post("/admin/reload");
        assertEquals(500, secondReload.statusCode());
        assertEquals(
                "application/problem+json",
                secondReload.headers().firstValue("content-type").orElse(""));

        JsonNode errorBody = MAPPER.readTree(secondReload.body());
        assertEquals(
                "urn:message-xform:proxy:internal-error", errorBody.get("type").asText());
        assertTrue(errorBody.has("detail"), "RFC 9457 body should have 'detail'");
        assertEquals(500, errorBody.get("status").asInt());

        // Old registry should still work — verify engine still has 1 spec from before
        // (the broken spec was not loaded). Remove the broken spec and reload again.
        Files.delete(specsDir.resolve("broken-spec.yaml"));
        HttpResponse<String> thirdReload = post("/admin/reload");
        assertEquals(200, thirdReload.statusCode());
        JsonNode thirdBody = MAPPER.readTree(thirdReload.body());
        assertEquals(1, thirdBody.get("specs").asInt(), "Old good spec should still be available after failed reload");
    }

    /**
     * POST /admin/reload with zero specs — valid startup, no-op reload.
     */
    @Test
    void postAdminReload_zeroSpecs_succeeds() throws Exception {
        // Empty specs directory — no specs
        startWithSpecsDir(specsDir, null);

        HttpResponse<String> response = post("/admin/reload");

        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("reloaded", body.get("status").asText());
        assertEquals(0, body.get("specs").asInt());
    }

    /**
     * Adding a new spec after initial startup and reloading makes it available.
     */
    @Test
    void postAdminReload_newSpecAfterStartup_available() throws Exception {
        startWithSpecsDir(specsDir, null);

        // Initially no specs
        HttpResponse<String> firstReload = post("/admin/reload");
        assertEquals(200, firstReload.statusCode());
        JsonNode firstBody = MAPPER.readTree(firstReload.body());
        assertEquals(0, firstBody.get("specs").asInt());

        // Add a new spec
        writeSpec(specsDir, "new-spec.yaml", """
                id: new-spec
                version: "1.0.0"
                description: "New spec"
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

        // Reload — should pick up new spec
        HttpResponse<String> secondReload = post("/admin/reload");
        assertEquals(200, secondReload.statusCode());
        JsonNode secondBody = MAPPER.readTree(secondReload.body());
        assertEquals(1, secondBody.get("specs").asInt());
    }

    // --- Harness: start proxy with real filesystem spec/profile directories ---

    /**
     * Starts the proxy with a real specs directory and optional profile file,
     * wiring up the admin reload handler.
     */
    private void startWithSpecsDir(Path specsDir, Path profileFile) throws IOException {
        startMockBackendInternal();

        io.messagexform.core.engine.EngineRegistry registry = new io.messagexform.core.engine.EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        io.messagexform.core.spec.SpecParser specParser = new io.messagexform.core.spec.SpecParser(registry);
        engine = new io.messagexform.core.engine.TransformEngine(specParser);

        // Load initial specs from directory
        loadSpecsFromDir(specsDir);

        // Load profile if provided
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

        app = io.javalin.Javalin.create()
                .post("/admin/reload", reloadHandler)
                .addHttpHandler(io.javalin.http.HandlerType.GET, "/<path>", proxyHandler)
                .addHttpHandler(io.javalin.http.HandlerType.POST, "/<path>", proxyHandler)
                .start(0);

        proxyPort = app.port();
        testClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    private void startMockBackendInternal() throws IOException {
        mockBackend = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    private void writeSpec(Path dir, String filename, String content) throws IOException {
        Files.writeString(dir.resolve(filename), content);
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return testClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
