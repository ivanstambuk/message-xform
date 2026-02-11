package io.messagexform.core.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
import java.util.List;
import java.util.Map;

/**
 * Factory methods for constructing {@link Message} instances in tests.
 *
 * <p>
 * These methods bridge from the old {@code JsonNode}-based constructors to
 * the new port types ({@code MessageBody}, {@code HttpHeaders},
 * {@code SessionContext}), keeping test migration simple.
 */
public final class TestMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestMessages() {
        /* utility class */
    }

    /**
     * Creates a request-style Message from a JsonNode body and header maps.
     * This is the most common test pattern — mirrors the old 8-arg Message
     * constructor.
     */
    public static Message request(
            JsonNode body,
            Map<String, String> headers,
            Map<String, List<String>> headersAll,
            String contentType,
            String requestPath,
            String requestMethod,
            String queryString) {
        return new Message(
                toBody(body, contentType),
                toHeaders(headers, headersAll),
                null,
                requestPath,
                requestMethod,
                queryString,
                SessionContext.empty());
    }

    /**
     * Creates a response-style Message from a JsonNode body, header maps, and
     * status.
     * Mirrors the old 8-arg Message constructor for response transforms.
     */
    public static Message response(
            JsonNode body,
            Map<String, String> headers,
            Map<String, List<String>> headersAll,
            int statusCode,
            String contentType,
            String requestPath,
            String requestMethod,
            String queryString) {
        return new Message(
                toBody(body, contentType),
                toHeaders(headers, headersAll),
                statusCode,
                requestPath,
                requestMethod,
                queryString,
                SessionContext.empty());
    }

    /**
     * Creates a Message with the minimum fields — just a JSON body.
     * Useful for simple transform tests.
     */
    public static Message ofJson(JsonNode body) {
        return new Message(
                toBody(body, "application/json"), HttpHeaders.empty(), null, null, null, null, SessionContext.empty());
    }

    /**
     * Creates a Message with a JSON body and status code.
     */
    public static Message ofJson(JsonNode body, int statusCode) {
        return new Message(
                toBody(body, "application/json"),
                HttpHeaders.empty(),
                statusCode,
                null,
                null,
                null,
                SessionContext.empty());
    }

    /**
     * Creates a Message with a JSON body, headers, and request metadata.
     */
    public static Message ofJson(JsonNode body, Map<String, String> headers, String requestPath, String requestMethod) {
        return request(body, headers, Map.of(), "application/json", requestPath, requestMethod, null);
    }

    /**
     * Creates a Message with a JSON string body.
     */
    public static Message ofJsonString(String json) {
        return new Message(MessageBody.json(json), HttpHeaders.empty(), null, null, null, null, SessionContext.empty());
    }

    /** Converts a JsonNode to MessageBody. */
    public static MessageBody toBody(JsonNode node, String contentType) {
        if (node == null || node instanceof NullNode) {
            return MessageBody.empty();
        }
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(node);
            if (contentType != null && !contentType.isEmpty()) {
                return MessageBody.of(bytes, io.messagexform.core.model.MediaType.fromContentType(contentType));
            }
            return MessageBody.json(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JsonNode for test", e);
        }
    }

    /** Converts a JsonNode to MessageBody with JSON media type. */
    public static MessageBody toBody(JsonNode node) {
        return toBody(node, "application/json");
    }

    /**
     * Converts header maps to HttpHeaders. Merges both maps: headersAll provides
     * the multi-value entries, and headers fills in any remaining single-value
     * entries not already present in headersAll.
     */
    public static HttpHeaders toHeaders(Map<String, String> headers, Map<String, List<String>> headersAll) {
        boolean hasMulti = headersAll != null && !headersAll.isEmpty();
        boolean hasSingle = headers != null && !headers.isEmpty();

        if (hasMulti && hasSingle) {
            // Merge: start from headersAll, add missing keys from headers
            var merged = new java.util.LinkedHashMap<String, List<String>>(headersAll);
            headers.forEach((k, v) -> merged.putIfAbsent(k, List.of(v)));
            return HttpHeaders.ofMulti(merged);
        }
        if (hasMulti) {
            return HttpHeaders.ofMulti(headersAll);
        }
        if (hasSingle) {
            return HttpHeaders.of(headers);
        }
        return HttpHeaders.empty();
    }

    /** Parses a MessageBody back to JsonNode for test assertions. */
    public static JsonNode parseBody(MessageBody body) {
        if (body == null || body.isEmpty()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(body.content());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse MessageBody in test assertion", e);
        }
    }

    /**
     * Converts a JsonNode session context to a {@link SessionContext}.
     * If the node is null or NullNode, returns {@code SessionContext.empty()}.
     */
    @SuppressWarnings("unchecked")
    public static SessionContext toSessionContext(JsonNode sessionNode) {
        if (sessionNode == null || sessionNode.isNull()) {
            return SessionContext.empty();
        }
        // Convert the entire JsonNode tree to a Map<String, Object> preserving types
        java.util.Map<String, Object> map = MAPPER.convertValue(
                sessionNode, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        return SessionContext.of(map);
    }
}
