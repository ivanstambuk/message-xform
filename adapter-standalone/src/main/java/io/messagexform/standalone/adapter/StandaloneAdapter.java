package io.messagexform.standalone.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.javalin.http.Context;
import io.messagexform.core.model.Message;
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
        // Parse JSON body — NullNode for absent or empty bodies
        JsonNode body = parseBody(ctx.body());

        // Build single-value header map with lowercase keys (FR-004-09)
        Map<String, String> headers = normalizeHeaders(ctx.headerMap());

        // Build multi-value header map from servlet request (FR-004-09)
        Map<String, List<String>> headersAll = extractHeadersAll(ctx);

        String contentType = ctx.contentType();
        String requestPath = ctx.path();
        String requestMethod = ctx.method().name();
        String queryString = ctx.queryString();

        LOG.debug("wrapRequest: {} {} (body={} bytes, headers={})",
                requestMethod, requestPath,
                ctx.body() != null ? ctx.body().length() : 0,
                headers.size());

        return new Message(body, headers, headersAll, null, contentType,
                requestPath, requestMethod, queryString);
    }

    @Override
    public Message wrapResponse(Context ctx) {
        // Placeholder — implemented in T-004-18
        throw new UnsupportedOperationException("wrapResponse not yet implemented (T-004-18)");
    }

    @Override
    public void applyChanges(Message transformedMessage, Context ctx) {
        // Placeholder — implemented in T-004-19
        throw new UnsupportedOperationException("applyChanges not yet implemented (T-004-19)");
    }

    /**
     * Parses a JSON body string into a {@link JsonNode}.
     * Returns {@link NullNode} for null, empty, or whitespace-only bodies.
     */
    private static JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(body).deepCopy();
        } catch (Exception e) {
            // JSON parse errors will be handled upstream (ProxyHandler returns 400)
            throw new IllegalArgumentException("Failed to parse JSON body", e);
        }
    }

    /**
     * Normalizes header names to lowercase, producing a single-value map
     * (first-value semantics). Returns a deep copy of the map (ADR-0013).
     */
    private static Map<String, String> normalizeHeaders(Map<String, String> rawHeaders) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (rawHeaders != null) {
            rawHeaders.forEach((name, value) -> normalized.putIfAbsent(name.toLowerCase(), value));
        }
        return normalized;
    }

    /**
     * Extracts multi-value headers from the servlet request with lowercase
     * key normalization (FR-004-09). Returns a deep copy.
     */
    private static Map<String, List<String>> extractHeadersAll(Context ctx) {
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
        return headersAll;
    }
}
