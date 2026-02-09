package io.messagexform.standalone.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for {@link StandaloneMain} startup sequence
 * (T-004-46, FR-004-27, S-004-44).
 *
 * <p>
 * Verifies the full startup sequence: load config → register engines →
 * load specs/profiles → init HttpClient → start Javalin → start FileWatcher.
 * The proxy must start successfully and begin accepting requests.
 */
class StartupSequenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer mockBackend;
    private ProxyApp proxyApp;

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        if (proxyApp != null) {
            proxyApp.stop();
        }
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    @Test
    @DisplayName("S-004-44: valid config + specs + profile → server starts → accepts requests")
    void validStartup_serverStartsAndAcceptsRequests() throws Exception {
        // Start a mock backend
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            String body = "{\"echo\":\"ok\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockBackend.start();

        // Create spec, profile, and config files in temp dir
        Path specsDir = tempDir.resolve("specs");
        Files.createDirectories(specsDir);
        Path profilesDir = tempDir.resolve("profiles");
        Files.createDirectories(profilesDir);

        // Simple passthrough spec
        Files.writeString(specsDir.resolve("test-spec.yaml"), """
            id: test-spec
            version: "1.0.0"
            description: "Passthrough transform"

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

        // Profile matching /api/*
        Files.writeString(profilesDir.resolve("test-profile.yaml"), """
            profile: test-profile
            version: "1.0.0"
            description: "Test profile for startup"

            transforms:
              - spec: test-spec@1.0.0
                direction: request
                match:
                  path: "/api/*"
                  method: "*"
            """);

        // Config file
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            proxy:
              host: "127.0.0.1"
              port: 0
            backend:
              host: "127.0.0.1"
              port: %d
            engine:
              specs-dir: "%s"
              profile: "%s"
            reload:
              enabled: false
            """.formatted(
                        backendPort,
                        specsDir.toString().replace("\\", "/"),
                        profilesDir.resolve("test-profile.yaml").toString().replace("\\", "/")));

        // Start proxy via ProxyApp
        proxyApp = ProxyApp.start(new String[] {"--config", configFile.toString()});

        int proxyPort = proxyApp.port();
        assertTrue(proxyPort > 0, "Proxy should bind to a port");

        // Verify the proxy accepts requests (passthrough)
        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/test"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("ok", body.get("echo").asText());

        // Verify health endpoint works
        HttpResponse<String> healthResponse = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + proxyPort + "/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, healthResponse.statusCode());
        JsonNode healthBody = MAPPER.readTree(healthResponse.body());
        assertEquals("UP", healthBody.get("status").asText());
    }

    @Test
    @DisplayName("NFR-004-01: startup time < 3 seconds (soft check)")
    void startupTime_underThreeSeconds() throws Exception {
        // Start a mock backend
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int backendPort = mockBackend.getAddress().getPort();
        mockBackend.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        mockBackend.start();

        // Create minimal config
        Path specsDir = tempDir.resolve("specs");
        Files.createDirectories(specsDir);

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(
                configFile, """
            proxy:
              host: "127.0.0.1"
              port: 0
            backend:
              host: "127.0.0.1"
              port: %d
            engine:
              specs-dir: "%s"
            reload:
              enabled: false
            """.formatted(backendPort, specsDir.toString().replace("\\", "/")));

        long start = System.nanoTime();
        proxyApp = ProxyApp.start(new String[] {"--config", configFile.toString()});
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertTrue(proxyApp.port() > 0, "Proxy should bind to a port");
        assertTrue(elapsed < 3000, "Startup should complete in < 3s, took " + elapsed + "ms");
    }
}
