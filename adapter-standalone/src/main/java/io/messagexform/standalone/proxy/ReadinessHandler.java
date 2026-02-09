package io.messagexform.standalone.proxy;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Readiness probe handler (FR-004-22, CFG-004-34/35/36).
 *
 * <p>
 * Returns {@code 200 OK} with
 * {@code {"status":"READY","engine":"loaded","backend":"reachable"}}
 * when:
 * <ol>
 * <li>The {@link io.messagexform.core.engine.TransformEngine} has loaded
 * its specs (engine ready).</li>
 * <li>The backend is reachable via a TCP connect to
 * {@code backend.host:backend.port} within the configured connect
 * timeout.</li>
 * </ol>
 *
 * <p>
 * Returns {@code 503 Service Unavailable} with appropriate body when either
 * check fails:
 * <ul>
 * <li>Engine not loaded → {@code {"status":"NOT_READY"}}</li>
 * <li>Backend unreachable →
 * {@code {"status":"NOT_READY","reason":"backend_unreachable"}}</li>
 * </ul>
 *
 * <p>
 * Designed for Kubernetes {@code readinessProbe} or equivalent probes.
 */
public final class ReadinessHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ReadinessHandler.class);

    private static final String READY_RESPONSE =
            "{\"status\":\"READY\",\"engine\":\"loaded\",\"backend\":\"reachable\"}";
    private static final String NOT_READY_ENGINE = "{\"status\":\"NOT_READY\"}";
    private static final String NOT_READY_BACKEND = "{\"status\":\"NOT_READY\",\"reason\":\"backend_unreachable\"}";

    private final BooleanSupplier engineLoaded;
    private final String backendHost;
    private final int backendPort;
    private final int connectTimeoutMs;

    /**
     * Creates a readiness handler.
     *
     * @param engineLoaded     supplier that returns {@code true} when the
     *                         engine has loaded its specs
     * @param backendHost      backend hostname for TCP connect check
     * @param backendPort      backend port for TCP connect check
     * @param connectTimeoutMs TCP connect timeout in milliseconds
     */
    public ReadinessHandler(BooleanSupplier engineLoaded, String backendHost, int backendPort, int connectTimeoutMs) {
        this.engineLoaded = engineLoaded;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void handle(Context ctx) {
        ctx.contentType("application/json");

        // Check 1: Engine loaded?
        if (!engineLoaded.getAsBoolean()) {
            LOG.debug("Readiness check: engine not loaded");
            ctx.status(503);
            ctx.result(NOT_READY_ENGINE);
            return;
        }

        // Check 2: Backend reachable via TCP connect?
        if (!isBackendReachable()) {
            LOG.debug("Readiness check: backend unreachable at {}:{}", backendHost, backendPort);
            ctx.status(503);
            ctx.result(NOT_READY_BACKEND);
            return;
        }

        ctx.status(200);
        ctx.result(READY_RESPONSE);
    }

    /**
     * Attempts a TCP connect to the backend within the configured timeout.
     *
     * @return {@code true} if the connection succeeds, {@code false} otherwise
     */
    private boolean isBackendReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backendHost, backendPort), connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
