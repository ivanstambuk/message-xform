package io.messagexform.pingaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import com.pingidentity.pa.sdk.identity.Identity;
import com.pingidentity.pa.sdk.identity.OAuthTokenMetadata;
import com.pingidentity.pa.sdk.identity.SessionStateSupport;
import com.pingidentity.pa.sdk.policy.AccessException;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.spi.GatewayAdapter;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PingAccess {@link GatewayAdapter} implementation (FR-002-01).
 *
 * <p>
 * Bridges PingAccess {@link Exchange} data to the core engine's {@link Message}
 * record. The adapter creates <strong>deep copies</strong> of native data
 * (ADR-0013), handles JSON parse failures internally (spec §FR-002-01), and
 * tracks body parse state via {@link #isBodyParseFailed()}.
 *
 * <p>
 * <strong>Thread safety:</strong> A single adapter instance is used per
 * request/response cycle within a single thread (PingAccess rule lifecycle).
 * The {@code bodyParseFailed} flag is reset at the start of each wrap call
 * (NFR-002-03).
 */
class PingAccessAdapter implements GatewayAdapter<Exchange> {

    private static final Logger LOG = LoggerFactory.getLogger(PingAccessAdapter.class);

    /** Headers excluded from diff-based application (Constraint 3). */
    private static final Set<String> PROTECTED_HEADERS = Set.of("content-length", "transfer-encoding");

    private final ObjectMapper objectMapper;

    /**
     * Tracks whether the most recent body parse failed. Reset at the start
     * of each {@code wrapRequest()} / {@code wrapResponse()} call. Used by
     * {@code MessageTransformRule} to determine whether to skip body transforms
     * and preserve the original raw body bytes (spec §FR-002-01, S-002-08).
     */
    private boolean bodyParseFailed;

    PingAccessAdapter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    // ── GatewayAdapter<Exchange> ──

    @Override
    public Message wrapRequest(Exchange exchange) {
        bodyParseFailed = false;

        Request request = exchange.getRequest();

        // T-002-03: URI split → path + query string
        String uri = request.getUri();
        String requestPath;
        String queryString;
        int qIndex = uri.indexOf('?');
        if (qIndex >= 0) {
            requestPath = uri.substring(0, qIndex);
            queryString = uri.substring(qIndex + 1);
        } else {
            requestPath = uri;
            queryString = null;
        }

        // T-002-06: method
        String requestMethod = request.getMethod().getName();

        // T-002-04: header mapping
        HttpHeaders httpHeaders = mapHeaders(request.getHeaders());

        // T-002-05: body read + JSON parse fallback
        MessageBody messageBody = readAndParseBody(request.getBody());

        // T-002-06: statusCode = null for requests (ADR-0020)
        // T-002-18/19: session = identity merge (FR-002-06)
        SessionContext session = buildSessionContext(exchange);
        return new Message(messageBody, httpHeaders, null, requestPath, requestMethod, queryString, session);
    }

    @Override
    public Message wrapResponse(Exchange exchange) {
        bodyParseFailed = false;

        Request request = exchange.getRequest();
        Response response = exchange.getResponse();

        // Request-side metadata (for profile matching on response transforms)
        String uri = request.getUri();
        String requestPath;
        String queryString;
        int qIndex = uri.indexOf('?');
        if (qIndex >= 0) {
            requestPath = uri.substring(0, qIndex);
            queryString = uri.substring(qIndex + 1);
        } else {
            requestPath = uri;
            queryString = null;
        }
        String requestMethod = request.getMethod().getName();

        // Response status
        int statusCode = response.getStatusCode();

        // Response headers
        HttpHeaders httpHeaders = mapHeaders(response.getHeaders());

        // Response body (same parse strategy as request)
        MessageBody messageBody = readAndParseBody(response.getBody());

        // T-002-18/19: session = identity merge (FR-002-06)
        SessionContext session = buildSessionContext(exchange);

        LOG.debug("wrapResponse: {} {} -> status={}", requestMethod, requestPath, statusCode);

        return new Message(messageBody, httpHeaders, statusCode, requestPath, requestMethod, queryString, session);
    }

    @Override
    public void applyChanges(Message transformedMessage, Exchange nativeTarget) {
        // Deliberate — see spec §FR-002-01, applyChanges Direction Strategy
        throw new UnsupportedOperationException("Use applyRequestChanges() or applyResponseChanges() directly");
    }

    // ── Body parse state ──

    /**
     * Returns {@code true} if the most recent body parse failed (malformed JSON,
     * read error, or non-JSON content). Used by {@code MessageTransformRule} to
     * skip body transforms and preserve the original raw body (S-002-08).
     */
    boolean isBodyParseFailed() {
        return bodyParseFailed;
    }

    // ── Session context construction (T-002-18, T-002-19, FR-002-06) ──

    /**
     * Builds a {@link SessionContext} from the PA exchange's Identity by
     * merging four layers in ascending precedence order (FR-002-06).
     *
     * <p>
     * <strong>Layer precedence:</strong>
     * <ol>
     * <li>L1 (base): Identity getters — {@code subject}, {@code mappedSubject},
     * {@code trackingId}, {@code tokenId}, {@code tokenExpiration}.</li>
     * <li>L2: OAuthTokenMetadata — {@code clientId}, {@code scopes},
     * {@code tokenType}, {@code realm}, {@code tokenExpiresAt},
     * {@code tokenRetrievedAt}.</li>
     * <li>L3: Identity.getAttributes() — OIDC claims / token introspection,
     * spread flat into the session.</li>
     * <li>L4 (top): SessionStateSupport.getAttributes() — dynamic session state,
     * spread flat into the session.</li>
     * </ol>
     *
     * <p>
     * Later layers override earlier layers on key collision. If the exchange
     * has no Identity ({@code exchange.getIdentity() == null}), returns
     * {@link SessionContext#empty()} (S-002-14).
     *
     * <p>
     * Jackson {@link JsonNode} values from L3/L4 are converted to plain Java
     * objects ({@code String}, {@code List}, {@code Map}, etc.) so that
     * {@link SessionContext} remains Jackson-free (ADR-0032, ADR-0033).
     *
     * @param exchange the native PA exchange
     * @return session context for engine evaluation
     */
    SessionContext buildSessionContext(Exchange exchange) {
        Identity identity = exchange.getIdentity();
        if (identity == null) {
            return SessionContext.empty();
        }

        Map<String, Object> flat = new LinkedHashMap<>();

        // Layer 1: PA identity fields (base)
        flat.put("subject", identity.getSubject());
        flat.put("mappedSubject", identity.getMappedSubject());
        flat.put("trackingId", identity.getTrackingId());
        flat.put("tokenId", identity.getTokenId());
        if (identity.getTokenExpiration() != null) {
            flat.put("tokenExpiration", identity.getTokenExpiration().toString());
        }

        // Layer 2: OAuth metadata
        OAuthTokenMetadata oauth = identity.getOAuthTokenMetadata();
        if (oauth != null) {
            flat.put("clientId", oauth.getClientId());
            flat.put("tokenType", oauth.getTokenType());
            flat.put("realm", oauth.getRealm());
            if (oauth.getExpiresAt() != null) {
                flat.put("tokenExpiresAt", oauth.getExpiresAt().toString());
            }
            if (oauth.getRetrievedAt() != null) {
                flat.put("tokenRetrievedAt", oauth.getRetrievedAt().toString());
            }
            List<String> scopesList = new ArrayList<>();
            if (oauth.getScopes() != null) {
                scopesList.addAll(oauth.getScopes());
            }
            flat.put("scopes", scopesList);
        }

        // Layer 3: OIDC claims / token attributes (spread into flat namespace)
        // Convert JsonNode → plain Java (ADR-0032: core port types are Jackson-free)
        JsonNode paAttributes = identity.getAttributes();
        if (paAttributes != null && paAttributes.isObject()) {
            paAttributes
                    .fields()
                    .forEachRemaining(entry -> flat.put(entry.getKey(), jsonNodeToJavaObject(entry.getValue())));
        }

        // Layer 4: Session state (spread into flat namespace — highest precedence)
        SessionStateSupport sss = identity.getSessionStateSupport();
        if (sss != null && sss.getAttributes() != null) {
            sss.getAttributes().forEach((key, value) -> flat.put(key, jsonNodeToJavaObject(value)));
        }

        return SessionContext.of(flat);
    }

    /**
     * Converts a Jackson {@link JsonNode} to a plain Java object.
     *
     * <p>
     * This ensures {@link SessionContext} remains Jackson-free (ADR-0032,
     * ADR-0033). The conversion is:
     * <ul>
     * <li>Text → {@code String}</li>
     * <li>Number (int) → {@code Integer}</li>
     * <li>Number (long/double) → {@code Long} or {@code Double}</li>
     * <li>Boolean → {@code Boolean}</li>
     * <li>Array → {@code List<Object>} (recursive)</li>
     * <li>Object → {@code Map<String, Object>} (recursive)</li>
     * <li>Null/missing → {@code null}</li>
     * </ul>
     */
    private Object jsonNodeToJavaObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isLong()) {
            return node.longValue();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                list.add(jsonNodeToJavaObject(element));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), jsonNodeToJavaObject(entry.getValue())));
            return map;
        }
        // Fallback: use text representation
        return node.asText();
    }

    // ── TransformContext construction (T-002-17, FR-002-13) ──

    /**
     * Builds a {@link io.messagexform.core.model.TransformContext} from the
     * PA exchange for engine evaluation (FR-002-13).
     *
     * <p>
     * Maps headers, query params (first-value), cookies (first-value), status,
     * and session context. On {@code URISyntaxException} from malformed query
     * strings, logs a warning and falls back to an empty query params map.
     *
     * @param exchange the native PA exchange
     * @param status   response status code ({@code null} for request phase)
     * @param session  session context (from identity merge or empty)
     * @return immutable transform context for engine evaluation
     */
    io.messagexform.core.model.TransformContext buildTransformContext(
            Exchange exchange, Integer status, SessionContext session) {
        // Headers — reuse the same mapHeaders() used by wrapRequest/wrapResponse
        HttpHeaders headers = mapHeaders(exchange.getRequest().getHeaders());

        // Query params — flatten String[] to first-value
        Map<String, String> queryParams = flattenQueryParams(exchange);

        // Cookies — flatten String[] to first-value
        Map<String, String> cookies = flattenCookies(exchange.getRequest().getHeaders());

        return new io.messagexform.core.model.TransformContext(
                headers, status, queryParams, cookies, session != null ? session : SessionContext.empty());
    }

    /**
     * Flattens PA's {@code Map<String, String[]>} query params to
     * {@code Map<String, String>} using first-value semantics (FR-002-13).
     *
     * <p>
     * On {@code URISyntaxException} from malformed URIs, logs a warning and
     * returns an empty map. JSLT expressions referencing {@code $queryParams}
     * will evaluate to {@code null} gracefully.
     */
    private Map<String, String> flattenQueryParams(Exchange exchange) {
        try {
            Map<String, String[]> raw = exchange.getRequest().getQueryStringParams();
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, String> flat = new LinkedHashMap<>(raw.size());
            for (Map.Entry<String, String[]> entry : raw.entrySet()) {
                String[] values = entry.getValue();
                if (values != null && values.length > 0) {
                    flat.put(entry.getKey(), values[0]);
                }
            }
            return flat;
        } catch (java.net.URISyntaxException e) {
            LOG.warn("Failed to parse query string — using empty queryParams: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Flattens PA's {@code Map<String, String[]>} cookies to
     * {@code Map<String, String>} using first-value semantics (FR-002-13).
     */
    private Map<String, String> flattenCookies(Headers paHeaders) {
        Map<String, String[]> raw = paHeaders.getCookies();
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> flat = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, String[]> entry : raw.entrySet()) {
            String[] values = entry.getValue();
            if (values != null && values.length > 0) {
                flat.put(entry.getKey(), values[0]);
            }
        }
        return flat;
    }

    // ── Direction-specific apply helpers (T-002-08, T-002-09, T-002-10) ──

    /**
     * Applies transformed message fields back to the request side of the exchange.
     *
     * @param transformed         the message after engine transformation
     * @param exchange            the native exchange
     * @param originalHeaderNames lowercase header names captured at wrap time
     */
    void applyRequestChanges(Message transformed, Exchange exchange, List<String> originalHeaderNames) {
        Request request = exchange.getRequest();

        // T-002-08: URI reconstruction (path + query)
        String uri = transformed.queryString() != null
                ? transformed.requestPath() + "?" + transformed.queryString()
                : transformed.requestPath();
        request.setUri(uri);

        // T-002-08: Method
        request.setMethod(Method.forName(transformed.requestMethod()));

        // T-002-10: Body replacement
        applyBody(transformed.body(), request, request.getHeaders());

        // T-002-09: Header diff
        applyHeaderDiff(originalHeaderNames, transformed.headers(), request.getHeaders());
    }

    /**
     * Applies transformed message fields back to the response side of the exchange.
     *
     * @param transformed         the message after engine transformation
     * @param exchange            the native exchange
     * @param originalHeaderNames lowercase header names captured at wrap time
     */
    void applyResponseChanges(Message transformed, Exchange exchange, List<String> originalHeaderNames) {
        Response response = exchange.getResponse();

        // T-002-08: Status code
        if (transformed.statusCode() != null) {
            response.setStatus(HttpStatus.forCode(transformed.statusCode()));
        }

        // T-002-10: Body replacement
        applyBody(transformed.body(), response, response.getHeaders());

        // T-002-09: Header diff
        applyHeaderDiff(originalHeaderNames, transformed.headers(), response.getHeaders());
    }

    /**
     * Like {@link #applyRequestChanges} but skips body replacement (S-002-08).
     *
     * <p>
     * Used when {@link #isBodyParseFailed()} is {@code true}: the original raw
     * bytes and Content-Type are preserved. Header and URI changes are still
     * applied.
     */
    void applyRequestChangesSkipBody(Message transformed, Exchange exchange, List<String> originalHeaderNames) {
        Request request = exchange.getRequest();

        // URI reconstruction (path + query)
        String uri = transformed.queryString() != null
                ? transformed.requestPath() + "?" + transformed.queryString()
                : transformed.requestPath();
        request.setUri(uri);

        // Method
        request.setMethod(Method.forName(transformed.requestMethod()));

        // Body intentionally NOT applied — original bytes preserved

        // Header diff
        applyHeaderDiff(originalHeaderNames, transformed.headers(), request.getHeaders());
    }

    /**
     * Like {@link #applyResponseChanges} but skips body replacement (S-002-08).
     *
     * <p>
     * Used when {@link #isBodyParseFailed()} is {@code true}: the original
     * response body bytes and Content-Type are preserved. Header and status
     * changes are still applied.
     */
    void applyResponseChangesSkipBody(Message transformed, Exchange exchange, List<String> originalHeaderNames) {
        Response response = exchange.getResponse();

        // Status code
        if (transformed.statusCode() != null) {
            response.setStatus(HttpStatus.forCode(transformed.statusCode()));
        }

        // Body intentionally NOT applied — original bytes preserved

        // Header diff
        applyHeaderDiff(originalHeaderNames, transformed.headers(), response.getHeaders());
    }

    // ── Internal helpers ──

    /**
     * Converts PA SDK {@link Headers} to core {@link HttpHeaders} (T-002-04).
     *
     * <p>
     * Iterates {@link Headers#getHeaderFields()} and groups values by
     * lowercase header name. The resulting map is passed to
     * {@link HttpHeaders#ofMulti(Map)} which provides both single-value and
     * multi-value views (spec §FR-002-01, Header normalization pattern).
     */
    HttpHeaders mapHeaders(Headers paHeaders) {
        List<HeaderField> fields = paHeaders.getHeaderFields();
        if (fields == null || fields.isEmpty()) {
            return HttpHeaders.empty();
        }

        Map<String, List<String>> multiMap = new LinkedHashMap<>();
        for (HeaderField field : fields) {
            String name = field.getHeaderName().toString().toLowerCase(Locale.ROOT);
            String value = field.getValue();
            multiMap.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return HttpHeaders.ofMulti(multiMap);
    }

    /**
     * Extracts lowercase header names from PA SDK {@link Headers} for diff
     * computation. Called at wrap time to capture a snapshot of original
     * header names before passing to the engine.
     *
     * @return list of lowercase header names (may contain duplicates from
     *         multi-value headers, but the diff only cares about presence)
     */
    List<String> snapshotHeaderNames(Headers paHeaders) {
        List<HeaderField> fields = paHeaders.getHeaderFields();
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (HeaderField field : fields) {
            names.add(field.getHeaderName().toString().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    /**
     * Pre-reads the body, validates as JSON, and returns a {@link MessageBody}
     * (T-002-05). Sets {@link #bodyParseFailed} on read error or parse failure.
     *
     * <p>
     * Strategy (spec §FR-002-01, JSON Parse Failure Strategy):
     * <ol>
     * <li>If {@code !body.isRead()}, call {@code body.read()} to load into memory.
     * On {@link IOException} or {@link AccessException}: return
     * {@code MessageBody.empty()} and set {@code bodyParseFailed = true}.</li>
     * <li>Get content bytes via {@code body.getContent()}.</li>
     * <li>If content is null or empty: return {@code MessageBody.empty()}
     * (not a parse failure).</li>
     * <li>Validate as JSON via {@code objectMapper.readTree(bytes)}.</li>
     * <li>On success: {@code MessageBody.json(bytes)}.</li>
     * <li>On failure: {@code MessageBody.empty()} + log warning +
     * {@code bodyParseFailed = true}.</li>
     * </ol>
     */
    private MessageBody readAndParseBody(Body paBody) {
        // Step 1: pre-read if needed
        if (!paBody.isRead()) {
            try {
                paBody.read();
            } catch (IOException e) {
                LOG.warn("Failed to read body: {}", e.getMessage(), e);
                bodyParseFailed = true;
                return MessageBody.empty();
            } catch (AccessException e) {
                LOG.warn("Body read denied ({}): {}", e.getErrorStatus(), e.getMessage(), e);
                bodyParseFailed = true;
                return MessageBody.empty();
            }
        }

        // Step 2-3: get content bytes
        byte[] content = paBody.getContent();
        if (content == null || content.length == 0) {
            return MessageBody.empty();
        }

        // Step 4-6: validate as JSON
        try {
            objectMapper.readTree(content);
            return MessageBody.json(content);
        } catch (IOException e) {
            LOG.warn("Body is not valid JSON ({} bytes), using empty body: {}", content.length, e.getMessage());
            bodyParseFailed = true;
            return MessageBody.empty();
        }
    }

    /**
     * Writes body bytes to a native PA message and sets Content-Type
     * (T-002-10). Empty bodies are not written — the original body is
     * preserved unchanged.
     *
     * @param body      the transformed body
     * @param paMessage the native PA message (Request or Response, both extend
     *                  {@code com.pingidentity.pa.sdk.http.Message})
     * @param paHeaders the native PA headers to set Content-Type on
     */
    private void applyBody(MessageBody body, com.pingidentity.pa.sdk.http.Message paMessage, Headers paHeaders) {
        if (!body.isEmpty()) {
            paMessage.setBodyContent(body.content());
            paHeaders.setFirstValue("Content-Type", "application/json; charset=utf-8");
        }
    }

    /**
     * Diff-based header application (T-002-09). Computes the diff between
     * original header names and transformed header names, excluding
     * protected headers ({@code content-length}, {@code transfer-encoding}).
     *
     * @param originalNames      lowercase original header names from wrap-time
     *                           snapshot
     * @param transformedHeaders the transformed headers from the engine
     * @param paHeaders          the native PA headers to write to
     */
    private void applyHeaderDiff(List<String> originalNames, HttpHeaders transformedHeaders, Headers paHeaders) {
        Map<String, String> transformedMap = transformedHeaders.toSingleValueMap();

        // Filter protected headers from both sets
        Set<String> origSet = new LinkedHashSet<>(originalNames);
        origSet.removeAll(PROTECTED_HEADERS);

        Set<String> transSet = new LinkedHashSet<>(transformedMap.keySet());
        transSet.removeAll(PROTECTED_HEADERS);

        // Headers in transformed but not in original → add (new headers)
        for (String name : transSet) {
            if (!origSet.contains(name)) {
                paHeaders.add(name, transformedMap.get(name));
            }
        }

        // Headers in both → update
        for (String name : transSet) {
            if (origSet.contains(name)) {
                paHeaders.setValues(name, List.of(transformedMap.get(name)));
            }
        }

        // Headers in original but not in transformed → remove
        for (String name : origSet) {
            if (!transSet.contains(name)) {
                paHeaders.removeFields(name);
            }
        }
    }
}
