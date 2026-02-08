package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.standalone.config.ProxyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for backend error handling (T-004-13, FR-004-24/25, S-004-18/19/20).
 *
 * <p>
 * Verifies that network failures and timeouts are wrapped in the
 * appropriate domain-specific exceptions:
 * <ul>
 * <li>{@link UpstreamConnectException} — host unreachable, connection refused
 * <li>{@link UpstreamTimeoutException} — response timeout exceeded
 * </ul>
 */
@DisplayName("UpstreamClient — backend error handling")
class UpstreamErrorTest {

    @Test
    @DisplayName("Backend connection refused → UpstreamConnectException (S-004-19)")
    void connectionRefused_throwsUpstreamConnectException() {
        // Port 1 is almost certainly not listening — triggers ConnectException
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("127.0.0.1")
                .backendPort(1)
                .backendConnectTimeoutMs(2000)
                .backendReadTimeoutMs(2000)
                .build();
        UpstreamClient client = new UpstreamClient(config);

        assertThatThrownBy(() -> client.forward("GET", "/api/test", null, null))
                .isInstanceOf(UpstreamConnectException.class)
                .hasMessageContaining("127.0.0.1")
                .hasCauseInstanceOf(java.net.ConnectException.class);
    }

    @Test
    @DisplayName("Backend unreachable (non-routable host) → UpstreamConnectException (S-004-18)")
    void unreachableHost_throwsUpstreamConnectException() {
        // 192.0.2.1 is RFC 5737 TEST-NET-1 — non-routable, triggers connect timeout
        ProxyConfig config = ProxyConfig.builder()
                .backendScheme("http")
                .backendHost("192.0.2.1")
                .backendPort(80)
                .backendConnectTimeoutMs(500) // Short timeout for test speed
                .backendReadTimeoutMs(500)
                .build();
        UpstreamClient client = new UpstreamClient(config);

        assertThatThrownBy(() -> client.forward("GET", "/api/test", null, null))
                .isInstanceOf(UpstreamConnectException.class)
                .hasMessageContaining("192.0.2.1");
    }

    @Test
    @DisplayName("Backend read timeout → UpstreamTimeoutException (S-004-20)")
    void readTimeout_throwsUpstreamTimeoutException() throws Exception {
        // Start a backend that accepts connections but never responds
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/slow", exchange -> {
            // Sleep longer than the read timeout — never sends response
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        try {
            ProxyConfig config = ProxyConfig.builder()
                    .backendScheme("http")
                    .backendHost("127.0.0.1")
                    .backendPort(port)
                    .backendConnectTimeoutMs(5000)
                    .backendReadTimeoutMs(500) // 500ms read timeout
                    .build();
            UpstreamClient client = new UpstreamClient(config);

            assertThatThrownBy(() -> client.forward("GET", "/api/slow", null, null))
                    .isInstanceOf(UpstreamTimeoutException.class)
                    .hasMessageContaining("127.0.0.1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Domain exceptions have correct hierarchy")
    void exceptionHierarchy() {
        assertThat(UpstreamException.class).isAssignableFrom(UpstreamConnectException.class);
        assertThat(UpstreamException.class).isAssignableFrom(UpstreamTimeoutException.class);
        assertThat(Exception.class).isAssignableFrom(UpstreamException.class);
    }
}
