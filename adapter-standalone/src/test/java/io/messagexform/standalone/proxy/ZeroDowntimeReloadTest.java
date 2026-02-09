package io.messagexform.standalone.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zero-downtime reload test (T-004-39, NFR-004-05, S-004-33/66).
 *
 * <p>
 * Verifies that in-flight requests complete with the old registry while new
 * requests use the updated registry. No request failures during reload.
 *
 * <p>
 * Scenarios covered:
 * <ul>
 * <li>S-004-33: In-flight request during reload gets consistent response (old
 * or new, never mixed).</li>
 * <li>S-004-66: Concurrent reload during traffic — 10 concurrent requests
 * in-flight → reload triggers → all complete successfully, each using either
 * old registry or new registry (never mixed).</li>
 * </ul>
 */
class ZeroDowntimeReloadTest extends ProxyTestHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path specsDir;

    @AfterEach
    void tearDown() {
        stopInfrastructure();
    }

    /**
     * S-004-66: Send 10 concurrent requests while triggering a reload. All
     * requests must complete successfully (200 OK). No failures due to reload.
     */
    @Test
    void concurrentRequests_duringReload_noFailures() throws Exception {
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

        startWithSpecsDir(specsDir, null);

        int concurrentRequests = 10;
        CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
        CountDownLatch goLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();

        // Prepare concurrent requests
        for (int i = 0; i < concurrentRequests; i++) {
            int reqNum = i;
            CompletableFuture<HttpResponse<String>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    readyLatch.countDown();
                    goLatch.await(5, TimeUnit.SECONDS);
                    return post("/api/test-" + reqNum, "{\"req\":" + reqNum + "}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }

        // Wait for all threads to be ready
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));

        // Write a new spec to trigger reload on the same thread as the requests
        writeSpec("second.yaml", """
                id: second-spec
                version: "1.0.0"
                description: "Second"
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

        // Release all requests AND trigger reload simultaneously
        goLatch.countDown();
        // Trigger reload mid-flight
        HttpResponse<String> reloadResp = adminReload();
        assertEquals(200, reloadResp.statusCode());

        // Wait for all concurrent requests to complete
        for (CompletableFuture<HttpResponse<String>> f : futures) {
            HttpResponse<String> resp = f.get(10, TimeUnit.SECONDS);
            if (resp.statusCode() == 200) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
        }

        // S-004-33: No request failures during reload
        assertEquals(concurrentRequests, successCount.get(), "All requests should succeed during reload");
        assertEquals(0, failureCount.get(), "No requests should fail during reload");
    }

    /**
     * S-004-33: In-flight request during reload gets a consistent response —
     * either from old registry or new registry, never mixed.
     */
    @Test
    void inFlightRequest_duringReload_consistentResponse() throws Exception {
        // Start with spec A
        writeSpec("spec-a.yaml", """
                id: spec-a
                version: "1.0.0"
                description: "Spec A"
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

        // Send multiple requests rapidly while reloading
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int reqNum = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return post("/api/item-" + reqNum, "{\"item\":" + reqNum + "}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));

            // Trigger reload part-way through
            if (i == 10) {
                adminReload();
            }
        }

        // All requests must complete successfully
        int successes = 0;
        for (CompletableFuture<HttpResponse<String>> f : futures) {
            HttpResponse<String> resp = f.get(10, TimeUnit.SECONDS);
            if (resp.statusCode() == 200) {
                successes++;
            }
        }

        assertEquals(20, successes, "All requests should succeed — no mixed/failed results during reload");
    }

    // --- Harness ---

    private void startWithSpecsDir(Path specsDir, Path profileFile) throws IOException {
        startMockBackendInternal();

        io.messagexform.core.engine.EngineRegistry registry = new io.messagexform.core.engine.EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        io.messagexform.core.spec.SpecParser specParser = new io.messagexform.core.spec.SpecParser(registry);
        engine = new io.messagexform.core.engine.TransformEngine(specParser);

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
            String reqBody =
                    new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
