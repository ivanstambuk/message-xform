package io.messagexform.standalone.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.messagexform.standalone.config.PoolConfig;
import io.messagexform.standalone.config.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for connection pool configuration (T-004-14, FR-004-18).
 *
 * <p>
 * Verifies that JVM system properties for connection pool tuning are set
 * from {@link PoolConfig} values when the {@link UpstreamClient} is created.
 */
@DisplayName("UpstreamClient — connection pool configuration")
class ConnectionPoolConfigTest {

    /** Clean up system properties after each test to avoid cross-contamination. */
    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("jdk.httpclient.connectionPoolSize");
        System.clearProperty("jdk.httpclient.keepalive.timeout");
    }

    @Test
    @DisplayName("Default PoolConfig → system properties set with defaults")
    void defaultPoolConfig_systemPropertiesSet() {
        ProxyConfig config = ProxyConfig.builder().backendHost("127.0.0.1").build();

        new UpstreamClient(config);

        // Default: maxConnections=100, idleTimeoutMs=60000
        assertThat(System.getProperty("jdk.httpclient.connectionPoolSize")).isEqualTo("100");
        // Idle timeout is in seconds for JDK property (60000ms → 60s)
        assertThat(System.getProperty("jdk.httpclient.keepalive.timeout")).isEqualTo("60");
    }

    @Test
    @DisplayName("Custom PoolConfig → system properties reflect custom values")
    void customPoolConfig_systemPropertiesReflectCustom() {
        PoolConfig custom = new PoolConfig(200, true, 120_000);
        ProxyConfig config =
                ProxyConfig.builder().backendHost("127.0.0.1").pool(custom).build();

        new UpstreamClient(config);

        assertThat(System.getProperty("jdk.httpclient.connectionPoolSize")).isEqualTo("200");
        assertThat(System.getProperty("jdk.httpclient.keepalive.timeout")).isEqualTo("120");
    }

    @Test
    @DisplayName("PoolConfig with keepAlive=false → keepalive timeout set to 0")
    void keepAliveFalse_timeoutZero() {
        PoolConfig noKeepAlive = new PoolConfig(50, false, 30_000);
        ProxyConfig config =
                ProxyConfig.builder().backendHost("127.0.0.1").pool(noKeepAlive).build();

        new UpstreamClient(config);

        assertThat(System.getProperty("jdk.httpclient.connectionPoolSize")).isEqualTo("50");
        // When keep-alive is disabled, set timeout to 0 to close idle connections
        // immediately
        assertThat(System.getProperty("jdk.httpclient.keepalive.timeout")).isEqualTo("0");
    }
}
