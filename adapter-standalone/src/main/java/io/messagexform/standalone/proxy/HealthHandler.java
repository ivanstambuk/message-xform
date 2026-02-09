package io.messagexform.standalone.proxy;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Liveness probe handler (FR-004-21, CFG-004-34/35).
 *
 * <p>
 * Returns a fixed {@code 200 OK} with {@code {"status": "UP"}} when the
 * JVM and HTTP server are running. This endpoint is NOT subject to
 * transform profile matching — it is registered as a dedicated Javalin
 * route that takes precedence over the proxy wildcard.
 *
 * <p>
 * Designed for Kubernetes {@code livenessProbe} or equivalent health
 * checkers.
 */
public final class HealthHandler implements Handler {

    private static final String HEALTH_RESPONSE = "{\"status\":\"UP\"}";

    @Override
    public void handle(Context ctx) {
        ctx.status(200);
        ctx.contentType("application/json");
        ctx.result(HEALTH_RESPONSE);
    }
}
