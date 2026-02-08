package io.messagexform.standalone.proxy;

import io.messagexform.standalone.config.ProxyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /**
     * Hop-by-hop headers per RFC 7230 §6.1 — stripped in both request and
     * response directions (FR-004-04, T-004-12).
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "transfer-encoding",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "upgrade");

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
     * @throws UpstreamConnectException if the backend is unreachable or refuses
     *                                  the connection
     * @throws UpstreamTimeoutException if the backend does not respond within
     *                                  the configured read timeout
     * @throws InterruptedException     if the thread is interrupted while waiting
     */
    public UpstreamResponse forward(String method, String path, String body, Map<String, String> headers)
            throws UpstreamException, InterruptedException {

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
                // Skip restricted and hop-by-hop headers
                if (isRestrictedHeader(name) || isHopByHop(name)) {
                    continue;
                }
                requestBuilder.header(name, entry.getValue());
            }
        }

        HttpRequest request = requestBuilder.build();

        LOG.debug("Forwarding {} {} to {}", method, path, targetUri);

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new UpstreamConnectException("Connect timeout to " + targetUri, e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new UpstreamTimeoutException("Read timeout from " + targetUri, e);
        } catch (java.net.ConnectException e) {
            throw new UpstreamConnectException("Connection refused by " + targetUri, e);
        } catch (java.io.IOException e) {
            throw new UpstreamConnectException("Failed to connect to " + targetUri, e);
        }

        // Normalize response headers to lowercase, first-value semantics,
        // stripping hop-by-hop headers (FR-004-04)
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            String lowerName = name.toLowerCase();
            if (!values.isEmpty() && !isHopByHop(lowerName)) {
                responseHeaders.put(lowerName, values.getFirst());
            }
        });

        String responseBody = response.body() != null ? response.body() : "";

        LOG.debug("Backend responded: {} {} → {}", method, path, response.statusCode());

        return new UpstreamResponse(response.statusCode(), responseHeaders, responseBody);
    }

    /**
     * Returns the underlying {@link HttpClient} — package-private for testing
     * (e.g. verifying HTTP/1.1 enforcement in T-004-10).
     */
    HttpClient httpClient() {
        return httpClient;
    }

    /**
     * Returns {@code true} for headers that JDK HttpClient manages internally
     * and must not be set manually.
     */
    private static boolean isRestrictedHeader(String name) {
        return "host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name);
    }

    /**
     * Returns {@code true} for hop-by-hop headers per RFC 7230 §6.1
     * that must be stripped before forwarding.
     */
    private static boolean isHopByHop(String lowerCaseName) {
        return HOP_BY_HOP_HEADERS.contains(lowerCaseName);
    }
}
