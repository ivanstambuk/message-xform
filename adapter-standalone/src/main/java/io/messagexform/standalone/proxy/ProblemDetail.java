package io.messagexform.standalone.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds RFC 9457 Problem Details responses for proxy-level errors
 * (FR-004-23/24/25/26, FR-004-13).
 *
 * <p>
 * This class handles errors that originate in the proxy itself — backend
 * connectivity failures, body size violations, malformed input — as opposed
 * to transform evaluation errors, which are built by the core module's
 * {@link io.messagexform.core.engine.ErrorResponseBuilder}.
 *
 * <p>
 * Every method returns an immutable {@link JsonNode} in RFC 9457 format:
 * <pre>{@code
 * {
 * "type": "urn:message-xform:proxy:backend-unreachable",
 * "title": "Backend Unreachable",
 * "status": 502,
 * "detail": "Connection refused to 127.0.0.1:8080",
 * "instance": "/api/orders"
 * }
 * }</pre>
 *
 * <p>
 * Thread-safe — all methods are stateless.
 */
public final class ProblemDetail {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // URN constants for proxy error types
    static final String URN_BACKEND_UNREACHABLE = "urn:message-xform:proxy:backend-unreachable";
    static final String URN_GATEWAY_TIMEOUT = "urn:message-xform:proxy:gateway-timeout";
    static final String URN_BODY_TOO_LARGE = "urn:message-xform:proxy:body-too-large";
    static final String URN_BAD_REQUEST = "urn:message-xform:proxy:bad-request";
    static final String URN_METHOD_NOT_ALLOWED = "urn:message-xform:proxy:method-not-allowed";
    static final String URN_INTERNAL_ERROR = "urn:message-xform:proxy:internal-error";

    private ProblemDetail() {
        // utility class
    }

    /**
     * Backend unreachable or connection refused (FR-004-24, S-004-18/20).
     *
     * @param detail       human-readable description
     * @param instancePath the request path
     * @return RFC 9457 JSON
     */
    public static JsonNode backendUnreachable(String detail, String instancePath) {
        return build(URN_BACKEND_UNREACHABLE, "Backend Unreachable", 502, detail, instancePath);
    }

    /**
     * Backend read timeout exceeded (FR-004-25, S-004-19).
     *
     * @param detail       human-readable description
     * @param instancePath the request path
     * @return RFC 9457 JSON
     */
    public static JsonNode gatewayTimeout(String detail, String instancePath) {
        return build(URN_GATEWAY_TIMEOUT, "Gateway Timeout", 504, detail, instancePath);
    }

    /**
     * Request or response body exceeds {@code proxy.max-body-bytes}
     * (FR-004-13, S-004-22/56).
     *
     * @param detail       human-readable description
     * @param status       413 for request, 502 for response
     * @param instancePath the request path
     * @return RFC 9457 JSON
     */
    public static JsonNode bodyTooLarge(String detail, int status, String instancePath) {
        return build(URN_BODY_TOO_LARGE, "Payload Too Large", status, detail, instancePath);
    }

    /**
     * Malformed or non-JSON request body on a profile-matched route
     * (FR-004-26, S-004-21/55).
     *
     * @param detail       human-readable description
     * @param instancePath the request path
     * @return RFC 9457 JSON
     */
    public static JsonNode badRequest(String detail, String instancePath) {
        return build(URN_BAD_REQUEST, "Bad Request", 400, detail, instancePath);
    }

    /**
     * Unknown HTTP method rejected (FR-004-05, S-004-23).
     *
     * @param detail       human-readable description
     * @param instancePath the request path
     * @return RFC 9457 JSON
     */
    public static JsonNode methodNotAllowed(String detail, String instancePath) {
        return build(URN_METHOD_NOT_ALLOWED, "Method Not Allowed", 405, detail, instancePath);
    }

    /**
     * Internal server error for admin operations (reload failure).
     *
     * @param detail       human-readable description
     * @param instancePath the request path
     * @return RFC 9457 JSON
     */
    public static JsonNode internalError(String detail, String instancePath) {
        return build(URN_INTERNAL_ERROR, "Internal Server Error", 500, detail, instancePath);
    }

    /**
     * Builds a standard RFC 9457 Problem Details JSON object.
     *
     * @param type         URN identifying the error category
     * @param title        short human-readable title
     * @param status       HTTP status code
     * @param detail       human-readable description
     * @param instancePath request path (may be null)
     * @return immutable {@link JsonNode}
     */
    static JsonNode build(String type, String title, int status, String detail, String instancePath) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("title", title);
        node.put("status", status);
        node.put("detail", detail);
        if (instancePath != null) {
            node.put("instance", instancePath);
        } else {
            node.putNull("instance");
        }
        return node;
    }
}
