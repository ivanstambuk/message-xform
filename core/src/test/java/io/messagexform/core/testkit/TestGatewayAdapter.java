package io.messagexform.core.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.model.Message;
import io.messagexform.core.spi.GatewayAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link GatewayAdapter} for running scenario tests and integration
 * tests end-to-end (T-001-49). Uses {@link TestMessage} as the "native"
 * gateway type.
 *
 * <p>
 * This adapter implements full copy-on-wrap semantics (ADR-0013):
 * <ul>
 * <li>{@code wrapRequest} / {@code wrapResponse} deep-copy the body and
 * headers, producing an independent {@link Message} instance.</li>
 * <li>{@code applyChanges} writes the transformed state back to the
 * {@link TestMessage}, including body, headers, status, path, method,
 * and query string.</li>
 * </ul>
 *
 * <p>
 * Designed for reuse by the parameterized scenario suite (T-001-50) and
 * any future integration tests that need a lightweight gateway adapter.
 */
public final class TestGatewayAdapter implements GatewayAdapter<TestMessage> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Message wrapRequest(TestMessage nativeRequest) {
        Objects.requireNonNull(nativeRequest, "nativeRequest must not be null");
        JsonNode body = parseAndCopy(nativeRequest.bodyJson());
        Map<String, String> firstHeaders = firstValueMap(nativeRequest.headers());
        Map<String, List<String>> allHeaders = deepCopyHeaders(nativeRequest.headers());
        return new Message(
                body,
                firstHeaders,
                allHeaders,
                null, // no status code for requests
                nativeRequest.contentType(),
                nativeRequest.path(),
                nativeRequest.method(),
                nativeRequest.queryString());
    }

    @Override
    public Message wrapResponse(TestMessage nativeResponse) {
        Objects.requireNonNull(nativeResponse, "nativeResponse must not be null");
        JsonNode body = parseAndCopy(nativeResponse.bodyJson());
        Map<String, String> firstHeaders = firstValueMap(nativeResponse.headers());
        Map<String, List<String>> allHeaders = deepCopyHeaders(nativeResponse.headers());
        return new Message(
                body,
                firstHeaders,
                allHeaders,
                nativeResponse.statusCode(),
                nativeResponse.contentType(),
                nativeResponse.path(),
                nativeResponse.method(),
                nativeResponse.queryString());
    }

    @Override
    public void applyChanges(Message transformedMessage, TestMessage nativeTarget) {
        Objects.requireNonNull(transformedMessage, "transformedMessage must not be null");
        Objects.requireNonNull(nativeTarget, "nativeTarget must not be null");
        nativeTarget.setBodyJson(transformedMessage.body().toString());
        nativeTarget.setStatusCode(transformedMessage.statusCode());

        // Write back headers
        Map<String, List<String>> newHeaders = new LinkedHashMap<>();
        transformedMessage.headersAll().forEach((name, values) -> newHeaders.put(name, new ArrayList<>(values)));
        nativeTarget.setHeaders(newHeaders);
        nativeTarget.setContentType(transformedMessage.contentType());

        // Write back URL fields
        nativeTarget.setPath(transformedMessage.requestPath());
        nativeTarget.setMethod(transformedMessage.requestMethod());
        nativeTarget.setQueryString(transformedMessage.queryString());
    }

    // --- Internal helpers
    // -----------------------------------------------------------

    private JsonNode parseAndCopy(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(json).deepCopy();
        } catch (Exception e) {
            return NullNode.getInstance();
        }
    }

    private Map<String, String> firstValueMap(Map<String, List<String>> multi) {
        Map<String, String> result = new LinkedHashMap<>();
        if (multi == null) return result;
        multi.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                result.put(k.toLowerCase(), v.get(0));
            }
        });
        return result;
    }

    private Map<String, List<String>> deepCopyHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (source == null) return copy;
        source.forEach((k, v) -> copy.put(k.toLowerCase(), new ArrayList<>(v)));
        return copy;
    }
}
