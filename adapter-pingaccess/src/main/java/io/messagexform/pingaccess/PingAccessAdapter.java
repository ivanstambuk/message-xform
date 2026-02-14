package io.messagexform.pingaccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
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
        // T-002-06: session = empty placeholder (FR-002-06 deferred)
        return new Message(
                messageBody, httpHeaders, null, requestPath, requestMethod, queryString, SessionContext.empty());
    }

    @Override
    public Message wrapResponse(Exchange exchange) {
        // Stub — implemented in I3 (T-002-07)
        throw new UnsupportedOperationException("wrapResponse not yet implemented");
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
    private HttpHeaders mapHeaders(Headers paHeaders) {
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
                LOG.warn("Failed to read request body: {}", e.getMessage(), e);
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
            LOG.warn("Request body is not valid JSON ({} bytes), using empty body: {}", content.length, e.getMessage());
            bodyParseFailed = true;
            return MessageBody.empty();
        }
    }
}
