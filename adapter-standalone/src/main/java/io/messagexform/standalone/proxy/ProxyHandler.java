package io.messagexform.standalone.proxy;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main HTTP proxy handler (IMPL-004-02, FR-004-01 through FR-004-06a,
 * FR-004-35).
 *
 * <p>
 * Orchestrates the full proxy cycle:
 * <ol>
 * <li>Wrap the inbound request (via {@link StandaloneAdapter})</li>
 * <li>Transform the request (via {@link TransformEngine})</li>
 * <li>Dispatch on {@link TransformResult} (FR-004-35)</li>
 * <li>Forward to backend (via {@link UpstreamClient})</li>
 * <li>Populate Javalin context with upstream response (FR-004-06a)</li>
 * <li>Wrap the response</li>
 * <li>Transform the response</li>
 * <li>Dispatch on response {@link TransformResult}</li>
 * <li>Write final response to client</li>
 * </ol>
 *
 * <p>
 * Request direction dispatch table (FR-004-35):
 * <ul>
 * <li>{@code SUCCESS} — forward {@code result.message()} to backend</li>
 * <li>{@code PASSTHROUGH} — forward raw request to backend unmodified</li>
 * <li>{@code ERROR} — return error response to client immediately; do NOT
 * forward</li>
 * </ul>
 *
 * <p>
 * Response direction dispatch table (FR-004-35):
 * <ul>
 * <li>{@code SUCCESS} — write transformed message to client via
 * {@code applyChanges}</li>
 * <li>{@code PASSTHROUGH} — return original backend response unmodified</li>
 * <li>{@code ERROR} — return error response to client</li>
 * </ul>
 *
 * <p>
 * This class is thread-safe — all state is local to each
 * {@link #handle(Context)} invocation.
 */
public final class ProxyHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyHandler.class);

    private final TransformEngine engine;
    private final StandaloneAdapter adapter;
    private final UpstreamClient upstreamClient;

    /**
     * Creates a new handler wiring up the engine, adapter, and upstream client.
     *
     * @param engine         the transform engine (no profile loaded → PASSTHROUGH)
     * @param adapter        the Javalin-to-Message adapter
     * @param upstreamClient the upstream HTTP client
     */
    public ProxyHandler(TransformEngine engine, StandaloneAdapter adapter, UpstreamClient upstreamClient) {
        this.engine = engine;
        this.adapter = adapter;
        this.upstreamClient = upstreamClient;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        // --- Step 1: Build TransformContext from request (cookies, query params) ---
        TransformContext transformContext = adapter.buildTransformContext(ctx);

        // --- Step 2: Wrap the inbound request → Message ---
        Message requestMessage = adapter.wrapRequest(ctx);

        // --- Step 3: Transform the request ---
        TransformResult requestResult = engine.transform(requestMessage, Direction.REQUEST, transformContext);

        // --- Step 4: Dispatch on request TransformResult (FR-004-35) ---
        String forwardMethod;
        String forwardPath;
        String forwardBody;
        Map<String, String> forwardHeaders;

        switch (requestResult.type()) {
            case SUCCESS -> {
                // Forward the transformed message to backend
                Message transformed = requestResult.message();
                forwardMethod = transformed.requestMethod();
                forwardPath = buildForwardPath(transformed.requestPath(), transformed.queryString());
                forwardBody = transformed.body() != null && !transformed.body().isNull()
                        ? transformed.body().toString()
                        : null;
                forwardHeaders = transformed.headers();
            }
            case PASSTHROUGH -> {
                // Forward the raw request unmodified — no JSON parse round-trip
                forwardMethod = ctx.method().name();
                forwardPath = buildForwardPath(ctx.path(), ctx.queryString());
                forwardBody = ctx.body();
                forwardHeaders = null; // UpstreamClient will forward without custom headers
                // Pass raw header map so backend receives original headers
                forwardHeaders = adapter.wrapRequest(ctx).headers();
            }
            case ERROR -> {
                // Return error to client immediately — do NOT forward to backend
                writeErrorResponse(ctx, requestResult);
                return;
            }
            default -> throw new IllegalStateException("Unknown TransformResult type: " + requestResult.type());
        }

        // --- Step 5: Forward to backend ---
        UpstreamResponse upstreamResponse =
                upstreamClient.forward(forwardMethod, forwardPath, forwardBody, forwardHeaders);

        // --- Step 6: Populate Javalin context with upstream response (FR-004-06a) ---
        ctx.result(upstreamResponse.body());
        ctx.status(upstreamResponse.statusCode());
        upstreamResponse.headers().forEach(ctx::header);

        // --- Step 7: Wrap the response → Message ---
        Message responseMessage = adapter.wrapResponse(ctx);

        // --- Step 8: Transform the response ---
        TransformResult responseResult = engine.transform(responseMessage, Direction.RESPONSE, transformContext);

        // --- Step 9: Dispatch on response TransformResult (FR-004-35) ---
        switch (responseResult.type()) {
            case SUCCESS -> {
                // Write transformed response to client via applyChanges
                adapter.applyChanges(responseResult.message(), ctx);
            }
            case PASSTHROUGH -> {
                // Response already written in step 6 — nothing to do.
                // Javalin will send the ctx.result() / ctx.status() / headers
                // that we set from the upstream response.
            }
            case ERROR -> {
                // Return error to client
                writeErrorResponse(ctx, responseResult);
            }
        }
    }

    /**
     * Builds the forward path including query string for
     * {@link UpstreamClient#forward}.
     *
     * @param path        the request path (e.g. {@code /api/users})
     * @param queryString the query string without leading {@code ?}
     *                    (e.g. {@code page=2&size=10}), may be null
     * @return the full path with query string (e.g.
     *         {@code /api/users?page=2&size=10})
     */
    private static String buildForwardPath(String path, String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return path;
        }
        return path + "?" + queryString;
    }

    /**
     * Writes a {@link TransformResult#ERROR} response to the client.
     *
     * @param ctx    the Javalin context
     * @param result the error result
     */
    private static void writeErrorResponse(Context ctx, TransformResult result) {
        ctx.status(result.errorStatusCode());
        ctx.contentType("application/problem+json");
        ctx.result(result.errorResponse().toString());

        LOG.warn("Transform error: status={}, body={}", result.errorStatusCode(), result.errorResponse());
    }
}
