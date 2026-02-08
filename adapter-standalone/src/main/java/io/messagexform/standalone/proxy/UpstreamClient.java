package io.messagexform.standalone.proxy;

import io.messagexform.standalone.config.ProxyConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK {@link HttpClient}-based upstream forwarder (IMPL-004-03).
 *
 * <p>
 * Forwards HTTP requests to the configured backend and returns the
 * response with status, headers, and body intact. Uses HTTP/1.1 for all
 * upstream connections (FR-004-33).
 *
 * <p>
 * This class is thread-safe — the underlying {@link HttpClient} is
 * thread-safe and designed for concurrent use.
 */
public final class UpstreamClient {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamClient.class);

    private final HttpClient httpClient;
    private final String backendBaseUrl;
    private final Duration readTimeout;

    /**
     * Creates an {@code UpstreamClient} for the given proxy configuration.
     *
     * @param config proxy configuration containing backend host, port, scheme,
     *               and timeout settings
     */
    public UpstreamClient(ProxyConfig config) {
        this.backendBaseUrl = config.backendScheme() + "://" + config.backendHost() + ":" + config.backendPort();
        this.readTimeout = Duration.ofMillis(config.backendReadTimeoutMs());

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(config.backendConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        LOG.debug("UpstreamClient initialized: backend={}", backendBaseUrl);
    }

    /**
     * Forwards an HTTP request to the configured backend.
     *
     * @param method  the HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)
     * @param path    the request path including optional query string
     *                (e.g. {@code /api/users?page=2})
     * @param body    the request body (null or empty for bodyless requests)
     * @param headers request headers to forward (lowercase keys); may be null
     * @return the upstream response with status code, headers, and body
     * @throws IOException          if a network error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public UpstreamResponse forward(String method, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {

        URI targetUri = URI.create(backendBaseUrl + path);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(readTimeout)
                .method(
                        method,
                        body != null && !body.isEmpty()
                                ? HttpRequest.BodyPublishers.ofString(body)
                                : HttpRequest.BodyPublishers.noBody());

        // Forward headers
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                // Skip restricted headers that JDK HttpClient manages internally
                if (isRestrictedHeader(name)) {
                    continue;
                }
                requestBuilder.header(name, entry.getValue());
            }
        }

        HttpRequest request = requestBuilder.build();

        LOG.debug("Forwarding {} {} to {}", method, path, targetUri);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Normalize response headers to lowercase, first-value semantics
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                responseHeaders.put(name.toLowerCase(), values.getFirst());
            }
        });

        String responseBody = response.body() != null ? response.body() : "";

        LOG.debug("Backend responded: {} {} → {}", method, path, response.statusCode());

        return new UpstreamResponse(response.statusCode(), responseHeaders, responseBody);
    }

    /**
     * Returns {@code true} for headers that JDK HttpClient manages internally
     * and must not be set manually.
     */
    private static boolean isRestrictedHeader(String name) {
        return "host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name);
    }
}
