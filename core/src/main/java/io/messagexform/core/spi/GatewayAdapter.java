package io.messagexform.core.spi;

import io.messagexform.core.model.Message;

/**
 * Gateway adapter SPI (SPI-001-04/05/06, ADR-0025). Bridges between a gateway
 * product's native request/response types and the engine's {@link Message}
 * envelope.
 *
 * <p>
 * Each gateway product (PingAccess, PingGateway, Kong, Standalone, etc.)
 * provides a concrete implementation of this interface. The type parameter
 * {@code <R>} represents the gateway-native request/response object.
 *
 * <h3>Copy-on-wrap semantics (ADR-0013)</h3>
 * <p>
 * Implementations MUST create a <strong>mutable deep copy</strong> of the
 * native message in {@link #wrapRequest} and {@link #wrapResponse}:
 * <ul>
 * <li>Deep-copy of the body content ({@code MessageBody}).</li>
 * <li>Copy of the headers ({@code HttpHeaders}).</li>
 * <li>Snapshot of the status code (response) or {@code null} (request).</li>
 * <li>Snapshot of session context ({@code SessionContext}).</li>
 * </ul>
 * The engine mutates this copy freely during transformation. On success,
 * {@link #applyChanges} writes the final state back to the native object.
 * On failure, the copy is discarded and the native message remains untouched.
 *
 * <h3>Lifecycle (ADR-0025)</h3>
 * <p>
 * This SPI is intentionally limited to per-request operations. Lifecycle
 * management — engine initialization, shutdown, reload triggering — is the
 * adapter's responsibility. Each adapter calls the {@code TransformEngine}
 * public API ({@code loadSpec}, {@code loadProfile}, {@code reload},
 * {@code registerEngine}) from within its gateway-native lifecycle hooks.
 *
 * <p>
 * Implementations MUST be thread-safe. A single adapter instance is
 * typically shared across concurrent gateway threads.
 *
 * @param <R> the gateway-native request/response type (e.g., PingAccess
 *            {@code Exchange}, a servlet {@code HttpServletRequest}, etc.)
 */
public interface GatewayAdapter<R> {

    /**
     * Wraps a gateway-native request into a {@link Message} (SPI-001-04).
     *
     * <p>
     * The returned {@code Message} is a deep copy of the native request's
     * data (ADR-0013). The engine will mutate this copy during transformation.
     *
     * <p>
     * Implementations MUST populate:
     * <ul>
     * <li>{@code body} — parsed JSON body as {@code MessageBody.json(bytes)},
     * or {@code MessageBody.empty()} if absent/unparseable</li>
     * <li>{@code headers} — {@code HttpHeaders} (lowercase keys per RFC 9110,
     * single-value via {@code first()}, multi-value via {@code all()})</li>
     * <li>{@code statusCode} — {@code null} for requests (ADR-0020)</li>
     * <li>{@code requestPath} — the request path (e.g., {@code /api/v1/users})</li>
     * <li>{@code requestMethod} — the HTTP method (e.g., {@code POST})</li>
     * <li>{@code queryString} — raw query string without leading {@code ?},
     * nullable</li>
     * <li>{@code session} — {@code SessionContext} from gateway identity,
     * or {@code SessionContext.empty()} if unauthenticated</li>
     * </ul>
     *
     * @param nativeRequest the gateway-native request object
     * @return a deep-copied {@link Message} representing the request
     */
    Message wrapRequest(R nativeRequest);

    /**
     * Wraps a gateway-native response into a {@link Message} (SPI-001-05).
     *
     * <p>
     * The returned {@code Message} is a deep copy of the native response's
     * data (ADR-0013). The engine will mutate this copy during transformation.
     *
     * <p>
     * Implementations MUST populate:
     * <ul>
     * <li>{@code body} — parsed JSON body as {@code MessageBody.json(bytes)},
     * or {@code MessageBody.empty()} if absent/unparseable</li>
     * <li>{@code headers} — {@code HttpHeaders} (lowercase keys,
     * single-value via {@code first()}, multi-value via {@code all()})</li>
     * <li>{@code statusCode} — the HTTP response status code</li>
     * <li>{@code requestPath} — the <em>original</em> request path (for profile
     * matching on response transforms)</li>
     * <li>{@code requestMethod} — the <em>original</em> HTTP method</li>
     * <li>{@code queryString} — raw query string from the original request,
     * nullable</li>
     * <li>{@code session} — {@code SessionContext} from gateway identity,
     * or {@code SessionContext.empty()} if unauthenticated</li>
     * </ul>
     *
     * @param nativeResponse the gateway-native response object
     * @return a deep-copied {@link Message} representing the response
     */
    Message wrapResponse(R nativeResponse);

    /**
     * Writes the transformed {@link Message} changes back to the gateway-native
     * object (SPI-001-06).
     *
     * <p>
     * Called <strong>only on successful transformation</strong>. On failure,
     * this method is NOT called — the native object remains untouched (ADR-0013).
     *
     * <p>
     * Implementations MUST write back:
     * <ul>
     * <li>The transformed body (serialized to the native format)</li>
     * <li>Modified headers (additions, removals, renames)</li>
     * <li>Modified status code (for response transforms)</li>
     * <li>Modified URL path, query string, and method (for request transforms)</li>
     * </ul>
     *
     * @param transformedMessage the message after engine transformation
     * @param nativeTarget       the original gateway-native object to update
     */
    void applyChanges(Message transformedMessage, R nativeTarget);
}
