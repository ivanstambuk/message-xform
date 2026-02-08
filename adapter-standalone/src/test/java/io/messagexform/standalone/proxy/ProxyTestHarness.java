package io.messagexform.standalone.proxy;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared test infrastructure for ProxyHandler integration tests.
 *
 * <p>
 * Provides:
 * <ul>
 * <li>A mock backend (JDK {@link HttpServer}) that echoes request details</li>
 * <li>A Javalin server with {@link ProxyHandler}</li>
 * <li>A JDK {@link HttpClient} for sending test requests</li>
 * <li>Captured request tracking for backend verification</li>
 * </ul>
 *
 * <p>
 * Subclasses call {@link #startWithSpecs(String[], String)} to start
 * infrastructure with specific spec/profile YAML fixtures.
 */
abstract class ProxyTestHarness {

    protected HttpServer mockBackend;
    protected int backendPort;
    protected Javalin app;
    protected int proxyPort;
    protected HttpClient testClient;
    protected TransformEngine engine;

    /** Records the last request received by the mock backend for each key. */
    protected final Map<String, ReceivedRequest> receivedRequests = new ConcurrentHashMap<>();

    record ReceivedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {}

    /**
     * Starts mock backend + Javalin proxy with the given specs and profile.
     *
     * @param specResourcePaths   classpath resource paths to spec YAML files
     * @param profileResourcePath classpath resource path to the profile YAML
     *                            file (null for no profile)
     */
    protected void startWithSpecs(String[] specResourcePaths, String profileResourcePath) throws IOException {
        startMockBackend();

        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(backendPort)
                .backendConnectTimeoutMs(5000)
                .backendReadTimeoutMs(5000)
                .build();

        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);

        // Load specs
        for (String specResource : specResourcePaths) {
            Path specPath = Path.of(
                    getClass().getClassLoader().getResource(specResource).getPath());
            engine.loadSpec(specPath);
        }

        // Load profile
        if (profileResourcePath != null) {
            Path profilePath = Path.of(
                    getClass().getClassLoader().getResource(profileResourcePath).getPath());
            engine.loadProfile(profilePath);
        }

        StandaloneAdapter adapter = new StandaloneAdapter();
        UpstreamClient upstreamClient = new UpstreamClient(config);
        ProxyHandler proxyHandler = new ProxyHandler(engine, adapter, upstreamClient);

        app = Javalin.create()
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

    /** Starts infrastructure with no specs/profile (passthrough mode). */
    protected void startPassthrough() throws IOException {
        startWithSpecs(new String[0], null);
    }

    private void startMockBackend() throws IOException {
        mockBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = mockBackend.getAddress().getPort();

        mockBackend.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String requestKey = path + (query != null ? "?" + query : "");

            receivedRequests.put(
                    requestKey,
                    new ReceivedRequest(
                            exchange.getRequestMethod(), path, query, exchange.getRequestHeaders(), requestBody));

            String method = exchange.getRequestMethod();
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Backend-Method", method);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            String responseBody = String.format(
                    "{\"method\":\"%s\",\"path\":\"%s\",\"query\":%s,\"body\":%s}",
                    method,
                    path,
                    query != null ? "\"" + query + "\"" : "null",
                    requestBody.isEmpty() ? "null" : requestBody);

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Backend-Method", method);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockBackend.start();
    }

    /** Registers a custom response for a specific path on the mock backend. */
    protected void registerBackendHandler(String path, int statusCode, String contentType, String responseBody) {
        mockBackend.createContext(path, exchange -> {
            String requestBodyStr = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String requestPath = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            receivedRequests.put(
                    requestPath,
                    new ReceivedRequest(
                            exchange.getRequestMethod(),
                            requestPath,
                            query,
                            exchange.getRequestHeaders(),
                            requestBodyStr));

            if (responseBody == null || responseBody.isEmpty()) {
                exchange.sendResponseHeaders(statusCode, -1);
                exchange.close();
                return;
            }

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    protected void stopInfrastructure() {
        if (app != null) {
            app.stop();
        }
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    /**
     * Case-insensitive header lookup from the received request headers map.
     * The JDK HttpServer normalizes header keys with first-letter caps.
     */
    protected List<String> getReceivedHeader(ReceivedRequest req, String headerName) {
        // Try common casings
        for (String variant : List.of(headerName, headerName.toLowerCase(), capitalize(headerName.toLowerCase()))) {
            List<String> vals = req.headers().get(variant);
            if (vals != null && !vals.isEmpty()) {
                return vals;
            }
        }
        return Collections.emptyList();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        // Capitalize each segment after '-'
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("-")) {
            if (!sb.isEmpty()) sb.append("-");
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }
}
