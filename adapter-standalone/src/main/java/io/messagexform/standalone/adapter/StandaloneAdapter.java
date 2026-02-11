package io.messagexform.standalone.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.spi.GatewayAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone proxy gateway adapter (FR-004-06, FR-004-07, FR-004-09).
 *
 * <p>
 * Implements {@link GatewayAdapter} for Javalin's {@link Context}, bridging
 * between the Javalin HTTP server and the core engine's {@link Message}
 * envelope.
 *
 * <p>
 * All wrap methods create <strong>deep copies</strong> of the native message
 * data (ADR-0013). Header names are normalized to lowercase (FR-004-09).
 *
 * <p>
 * This class is thread-safe — all state is local to each method invocation.
 */
public final class StandaloneAdapter implements GatewayAdapter<Context> {

    private static final Logger LOG = LoggerFactory.getLogger(StandaloneAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Message wrapRequest(Context ctx) {
        // Parse JSON body
        MessageBody body = parseBody(ctx.body(), ctx.contentType());

        // Build header map with lowercase keys (FR-004-09)
        HttpHeaders headers = buildHeaders(ctx);

        String requestPath = ctx.path();
        String requestMethod = ctx.method().name();
        String queryString = ctx.queryString();

        LOG.debug(
                "wrapRequest: {} {} (body={} bytes, headers={})",
                requestMethod,
                requestPath,
                ctx.body() != null ? ctx.body().length() : 0,
                headers.toSingleValueMap().size());

        return new Message(body, headers, null, requestPath, requestMethod, queryString, SessionContext.empty());
    }

    /**
     * Wraps a Javalin request into a {@link Message} with a {@link NullNode}
     * body, bypassing JSON body parsing. Used when the request body fails to
     * parse as JSON (FR-004-26) — this allows profile matching to proceed
     * on path/method without requiring a valid JSON body.
     *
     * @param ctx the Javalin request context
     * @return a {@link Message} with NullNode body and all other fields populated
     */
    public Message wrapRequestRaw(Context ctx) {
        HttpHeaders headers = buildHeaders(ctx);
        String requestPath = ctx.path();
        String requestMethod = ctx.method().name();
        String queryString = ctx.queryString();

        LOG.debug(
                "wrapRequestRaw: {} {} (body skipped, headers={})",
                requestMethod,
                requestPath,
                headers.toSingleValueMap().size());

        return new Message(
                MessageBody.empty(), headers, null, requestPath, requestMethod, queryString, SessionContext.empty());
    }

    @Override
    public Message wrapResponse(Context ctx) {
        // Read response body from ctx.result() (set by ProxyHandler)
        String contentType = null;

        // Read response headers from servlet response (FR-004-06a)
        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        for (String name : ctx.res().getHeaderNames()) {
            String lowerName = name.toLowerCase();
            headersAll.putIfAbsent(
                    lowerName,
                    Collections.unmodifiableList(new ArrayList<>(ctx.res().getHeaders(name))));
            if ("content-type".equals(lowerName) && contentType == null) {
                contentType = ctx.res().getHeader(name);
            }
        }
        HttpHeaders headers = HttpHeaders.ofMulti(headersAll);

        MessageBody body = parseBody(ctx.result(), contentType);

        int statusCode = ctx.statusCode();

        // Original request path/method for profile matching (FR-004-06b)
        String requestPath = ctx.path();
        String requestMethod = ctx.method().name();

        LOG.debug(
                "wrapResponse: {} {} → {} (body={} bytes, headers={})",
                requestMethod,
                requestPath,
                statusCode,
                ctx.result() != null ? ctx.result().length() : 0,
                headers.toSingleValueMap().size());

        // queryString is null for responses
        return new Message(body, headers, statusCode, requestPath, requestMethod, null, SessionContext.empty());
    }

    /**
     * Wraps a Javalin response into a {@link Message} with a {@link NullNode}
     * body, bypassing JSON body parsing. Used when the backend response body
     * is not valid JSON — allows profile matching to proceed on path/method
     * for response direction transforms.
     *
     * @param ctx the Javalin context (with upstream response data)
     * @return a {@link Message} with NullNode body and all other fields populated
     */
    public Message wrapResponseRaw(Context ctx) {
        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        for (String name : ctx.res().getHeaderNames()) {
            String lowerName = name.toLowerCase();
            headersAll.putIfAbsent(
                    lowerName,
                    Collections.unmodifiableList(new ArrayList<>(ctx.res().getHeaders(name))));
        }
        HttpHeaders headers = HttpHeaders.ofMulti(headersAll);

        int statusCode = ctx.statusCode();
        String requestPath = ctx.path();
        String requestMethod = ctx.method().name();

        LOG.debug(
                "wrapResponseRaw: {} {} → {} (body skipped, headers={})",
                requestMethod,
                requestPath,
                statusCode,
                headers.toSingleValueMap().size());

        return new Message(
                MessageBody.empty(), headers, statusCode, requestPath, requestMethod, null, SessionContext.empty());
    }

    @Override
    public void applyChanges(Message transformedMessage, Context ctx) {
        // Write body — empty MessageBody → empty body (FR-004-08)
        String bodyStr;
        if (transformedMessage.body() == null || transformedMessage.body().isEmpty()) {
            bodyStr = "";
        } else {
            bodyStr = transformedMessage.body().asString();
        }
        ctx.result(bodyStr);

        // Write headers
        transformedMessage.headers().toSingleValueMap().forEach(ctx::header);

        // Write status code
        if (transformedMessage.statusCode() != null) {
            ctx.status(transformedMessage.statusCode());
        }

        LOG.debug(
                "applyChanges: status={}, headers={}, body={} bytes",
                transformedMessage.statusCode(),
                transformedMessage.headers().toSingleValueMap().size(),
                bodyStr.length());
    }

    /**
     * Builds a {@link TransformContext} from the Javalin {@link Context},
     * extracting cookies, query parameters, headers, and status for
     * injection into the core engine's 3-arg transform call (FR-004-37,
     * FR-004-39).
     *
     * @param ctx the Javalin request context
     * @return a populated {@link TransformContext}
     */
    public TransformContext buildTransformContext(Context ctx) {
        HttpHeaders headers = buildHeaders(ctx);

        // Cookies from Javalin (already URL-decoded)
        Map<String, String> cookies = new LinkedHashMap<>(ctx.cookieMap());

        // Query params — first value only for multi-value (FR-004-39)
        Map<String, String> queryParams = extractQueryParams(ctx);

        return new TransformContext(headers, null, queryParams, cookies, SessionContext.empty());
    }

    /**
     * Parses a body string into a {@link MessageBody}.
     * Returns {@link MessageBody#empty()} for null or whitespace-only bodies.
     * Validates JSON by parsing (throws on invalid JSON).
     */
    private static MessageBody parseBody(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return MessageBody.empty();
        }
        try {
            // Validate JSON by parsing. This ensures malformed JSON is rejected.
            MAPPER.readTree(body);
            return MessageBody.json(body);
        } catch (Exception e) {
            // JSON parse errors will be handled upstream (ProxyHandler returns 400)
            throw new IllegalArgumentException("Failed to parse JSON body", e);
        }
    }

    /**
     * Builds an {@link HttpHeaders} from the Javalin request's multi-value
     * headers with lowercase key normalization (FR-004-09). Uses the servlet
     * request to capture all header values.
     */
    private static HttpHeaders buildHeaders(Context ctx) {
        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        var headerNames = ctx.req().getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                var values = ctx.req().getHeaders(name);
                List<String> valueList = new ArrayList<>();
                if (values != null) {
                    while (values.hasMoreElements()) {
                        valueList.add(values.nextElement());
                    }
                }
                headersAll.put(name.toLowerCase(), Collections.unmodifiableList(valueList));
            }
        }
        return HttpHeaders.ofMulti(headersAll);
    }

    /**
     * Extracts query parameters from the Javalin context with first-value
     * semantics for multi-value params (FR-004-39, S-004-76). Values are
     * already URL-decoded by Javalin.
     */
    private static Map<String, String> extractQueryParams(Context ctx) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        ctx.queryParamMap().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                queryParams.put(key, values.getFirst());
            }
        });
        return queryParams;
    }
}
