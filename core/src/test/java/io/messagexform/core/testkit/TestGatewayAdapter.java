package io.messagexform.core.testkit;

import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.MediaType;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
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

    @Override
    public Message wrapRequest(TestMessage nativeRequest) {
        Objects.requireNonNull(nativeRequest, "nativeRequest must not be null");
        MessageBody body = parseBodyJson(nativeRequest.bodyJson(), nativeRequest.contentType());
        HttpHeaders headers = toHttpHeaders(nativeRequest.headers());
        return new Message(
                body,
                headers,
                null, // no status code for requests
                nativeRequest.path(),
                nativeRequest.method(),
                nativeRequest.queryString(),
                SessionContext.empty());
    }

    @Override
    public Message wrapResponse(TestMessage nativeResponse) {
        Objects.requireNonNull(nativeResponse, "nativeResponse must not be null");
        MessageBody body = parseBodyJson(nativeResponse.bodyJson(), nativeResponse.contentType());
        HttpHeaders headers = toHttpHeaders(nativeResponse.headers());
        return new Message(
                body,
                headers,
                nativeResponse.statusCode(),
                nativeResponse.path(),
                nativeResponse.method(),
                nativeResponse.queryString(),
                SessionContext.empty());
    }

    @Override
    public void applyChanges(Message transformedMessage, TestMessage nativeTarget) {
        Objects.requireNonNull(transformedMessage, "transformedMessage must not be null");
        Objects.requireNonNull(nativeTarget, "nativeTarget must not be null");

        // Write back body — convert MessageBody bytes to String for TestMessage
        if (!transformedMessage.body().isEmpty()) {
            nativeTarget.setBodyJson(
                    new String(transformedMessage.body().content(), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            nativeTarget.setBodyJson(null);
        }
        nativeTarget.setStatusCode(transformedMessage.statusCode());

        // Write back headers
        Map<String, List<String>> newHeaders = new LinkedHashMap<>();
        transformedMessage
                .headers()
                .toMultiValueMap()
                .forEach((name, values) -> newHeaders.put(name, new ArrayList<>(values)));
        nativeTarget.setHeaders(newHeaders);
        nativeTarget.setContentType(transformedMessage.contentType());

        // Write back URL fields
        nativeTarget.setPath(transformedMessage.requestPath());
        nativeTarget.setMethod(transformedMessage.requestMethod());
        nativeTarget.setQueryString(transformedMessage.queryString());
    }

    // --- Internal helpers ---

    private MessageBody parseBodyJson(String json, String contentType) {
        if (json == null || json.isBlank()) {
            return MessageBody.empty();
        }
        MediaType mediaType = contentType != null ? MediaType.fromContentType(contentType) : MediaType.JSON;
        return MessageBody.of(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), mediaType);
    }

    private HttpHeaders toHttpHeaders(Map<String, List<String>> multi) {
        if (multi == null || multi.isEmpty()) {
            return HttpHeaders.empty();
        }
        // Lowercase keys during conversion
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        multi.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                normalized.put(k.toLowerCase(), new ArrayList<>(v));
            }
        });
        return HttpHeaders.ofMulti(normalized);
    }
}
