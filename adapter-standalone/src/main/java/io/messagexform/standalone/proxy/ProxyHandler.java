package io.messagexform.standalone.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    private final int maxBodyBytes;
    private final boolean forwardedHeadersEnabled;

    /**
     * Creates a new handler wiring up the engine, adapter, and upstream client.
     *
     * @param engine                  the transform engine (no profile loaded →
     *                                PASSTHROUGH)
     * @param adapter                 the Javalin-to-Message adapter
     * @param upstreamClient          the upstream HTTP client
     * @param maxBodyBytes            max request body size in bytes (≤ 0 for no
     *                                limit)
     * @param forwardedHeadersEnabled whether to inject X-Forwarded-* headers
     *                                (FR-004-36)
     */
    public ProxyHandler(TransformEngine engine, StandaloneAdapter adapter, UpstreamClient upstreamClient,
            int maxBodyBytes, boolean forwardedHeadersEnabled) {
        this.engine = engine;
        this.adapter = adapter;
        this.upstreamClient = upstreamClient;
        this.maxBodyBytes = maxBodyBytes;
        this.forwardedHeadersEnabled = forwardedHeadersEnabled;
    }

    private static final String REQUEST_ID_HEADER = "x-request-id";

    @Override
    public void handle(Context ctx) throws Exception {
        // --- Step 0: X-Request-ID extraction/generation (FR-004-38) ---
        String requestId = ctx.header("X-Request-ID");
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }
        ctx.header(REQUEST_ID_HEADER, requestId);

        // --- Step 0a: Request body size enforcement (FR-004-13, T-004-29) ---
        if (maxBodyBytes > 0) {
            long contentLength = ctx.contentLength();
            if (contentLength > maxBodyBytes) {
                LOG.warn("Request body too large: {} bytes (limit {})", contentLength, maxBodyBytes);
                writeProblemResponse(ctx, 413,
                        ProblemDetail.bodyTooLarge(
                                "Request body exceeds " + maxBodyBytes + " bytes",
                                413, ctx.path()));
                return;
            }
            // For chunked requests without Content-Length, check after reading
            String body = ctx.body();
            if (body.length() > maxBodyBytes) {
                LOG.warn("Request body too large: {} bytes (limit {})", body.length(), maxBodyBytes);
                writeProblemResponse(ctx, 413,
                        ProblemDetail.bodyTooLarge(
                                "Request body exceeds " + maxBodyBytes + " bytes",
                                413, ctx.path()));
                return;
            }
        }

        // --- Step 1: Build TransformContext from request (cookies, query params) ---
        TransformContext transformContext = adapter.buildTransformContext(ctx);

        // --- Step 2: Wrap the inbound request → Message ---
        // JSON parse may fail if the body is non-JSON or malformed.
        // FR-004-26: profile-matched routes reject non-JSON; unmatched
        // routes pass through without body parsing.
        Message requestMessage;
        boolean parseError = false;
        try {
            requestMessage = adapter.wrapRequest(ctx);
        } catch (IllegalArgumentException e) {
            // Build a minimal Message (NullNode body) for profile matching only.
            // If the engine returns PASSTHROUGH, forward raw.
            // If a profile would match (SUCCESS/ERROR), return 400.
            parseError = true;
            requestMessage = adapter.wrapRequestRaw(ctx);
        }

        // --- Step 3: Transform the request ---
        TransformResult requestResult = engine.transform(requestMessage, Direction.REQUEST, transformContext);

        // If parse failed, check if a profile matched → 400 Bad Request (FR-004-26)
        if (parseError && !requestResult.isPassthrough()) {
            LOG.warn("Non-JSON body on profile-matched route: {}", ctx.path());
            writeProblemResponse(ctx, 400, ProblemDetail.badRequest("Request body is not valid JSON", ctx.path()));
            return;
        }

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
                // Use already-constructed requestMessage headers (avoids re-parsing body)
                forwardHeaders = requestMessage.headers();
            }
            case ERROR -> {
                // Return error to client immediately — do NOT forward to backend
                writeErrorResponse(ctx, requestResult);
                return;
            }
            default -> throw new IllegalStateException("Unknown TransformResult type: " + requestResult.type());
        }

        // --- Step 4b: Inject X-Forwarded-* headers (FR-004-36, T-004-31) ---
        if (forwardedHeadersEnabled) {
            forwardHeaders = injectForwardedHeaders(forwardHeaders, ctx);
        }

        // --- Step 5: Forward to backend ---
        UpstreamResponse upstreamResponse;
        try {
            upstreamResponse = upstreamClient.forward(forwardMethod, forwardPath, forwardBody, forwardHeaders);
        } catch (UpstreamTimeoutException e) {
            LOG.warn("Backend timeout: {}", e.getMessage(), e);
            writeProblemResponse(ctx, 504, ProblemDetail.gatewayTimeout(e.getMessage(), ctx.path()));
            return;
        } catch (UpstreamResponseTooLargeException e) {
            LOG.warn("Backend response too large: {}", e.getMessage());
            writeProblemResponse(ctx, 502, ProblemDetail.bodyTooLarge(e.getMessage(), 502, ctx.path()));
            return;
        } catch (UpstreamConnectException e) {
            LOG.warn("Backend unreachable: {}", e.getMessage(), e);
            writeProblemResponse(ctx, 502, ProblemDetail.backendUnreachable(e.getMessage(), ctx.path()));
            return;
        }

        // --- Step 6: Populate Javalin context with upstream response (FR-004-06a) ---
        ctx.result(upstreamResponse.body());
        ctx.status(upstreamResponse.statusCode());
        // Forward response headers, but skip framing headers (content-length,
        // transfer-encoding) — Javalin/Jetty manages these based on the actual
        // body written to ctx.result(). If we forwarded them, a response
        // transformation that changes the body size would cause truncation.
        upstreamResponse.headers().forEach((name, value) -> {
            if (!"content-length".equalsIgnoreCase(name) && !"transfer-encoding".equalsIgnoreCase(name)) {
                ctx.header(name, value);
            }
        });

        // --- Step 7: Wrap the response → Message ---
        // Response body may not be JSON (e.g., passthrough text/plain backend).
        // Same pattern as request: if parse fails and no profile matches
        // (PASSTHROUGH), the response is already written in step 6.
        Message responseMessage;
        boolean responseParseError = false;
        try {
            responseMessage = adapter.wrapResponse(ctx);
        } catch (IllegalArgumentException e) {
            responseParseError = true;
            responseMessage = adapter.wrapResponseRaw(ctx);
        }

        // --- Step 8: Transform the response ---
        TransformResult responseResult = engine.transform(responseMessage, Direction.RESPONSE, transformContext);

        // If response parse failed and a profile matched → 502 (can't transform)
        if (responseParseError && !responseResult.isPassthrough()) {
            LOG.warn("Non-JSON response body on profile-matched route: {}", ctx.path());
            writeProblemResponse(
                    ctx, 502, ProblemDetail.backendUnreachable("Backend returned non-JSON response body", ctx.path()));
            return;
        }

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

    /**
     * Writes an RFC 9457 Problem Details response for proxy-level errors
     * (backend failures, body size violations, bad requests).
     *
     * @param ctx        the Javalin context
     * @param statusCode the HTTP status code
     * @param problem    the RFC 9457 JSON body
     */
    private static void writeProblemResponse(Context ctx, int statusCode, JsonNode problem) {
        ctx.status(statusCode);
        ctx.contentType("application/problem+json");
        ctx.result(problem.toString());
    }

    /**
     * Injects {@code X-Forwarded-For}, {@code X-Forwarded-Proto}, and
     * {@code X-Forwarded-Host} into the forwarded headers map (FR-004-36).
     *
     * <p>
     * If {@code X-Forwarded-For} already exists, the client IP is
     * <b>appended</b> (comma-separated) per RFC 7239.
     *
     * @param headers the current headers map (may be immutable)
     * @param ctx     the Javalin context (source of client IP, scheme, host)
     * @return a mutable map with forwarded headers injected
     */
    private static Map<String, String> injectForwardedHeaders(Map<String, String> headers, Context ctx) {
        Map<String, String> mutable = new LinkedHashMap<>(headers != null ? headers : Map.of());

        // X-Forwarded-For: append client IP
        String clientIp = ctx.ip();
        String existingXff = mutable.get("x-forwarded-for");
        if (existingXff != null && !existingXff.isEmpty()) {
            mutable.put("x-forwarded-for", existingXff + ", " + clientIp);
        } else {
            mutable.put("x-forwarded-for", clientIp);
        }

        // X-Forwarded-Proto: inbound scheme
        mutable.put("x-forwarded-proto", ctx.scheme());

        // X-Forwarded-Host: original Host header
        String host = ctx.header("Host");
        if (host != null && !host.isEmpty()) {
            mutable.put("x-forwarded-host", host);
        }

        return mutable;
    }
}
