package io.messagexform.standalone.config;

/**
 * Backend connection pool configuration (CFG-004-17..19, DO-004-04).
 *
 * @param maxConnections max concurrent connections to backend (CFG-004-17)
 * @param keepAlive      use HTTP keep-alive (CFG-004-18)
 * @param idleTimeoutMs  close idle connections after this duration in ms
 *                       (CFG-004-19)
 */
public record PoolConfig(int maxConnections, boolean keepAlive, int idleTimeoutMs) {

    /** Default pool configuration. */
    public static final PoolConfig DEFAULT = new PoolConfig(100, true, 60000);
}
