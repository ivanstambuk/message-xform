package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.UrlSpec;
import io.messagexform.core.spi.CompiledExpression;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies declarative URL rewrite operations to a {@link Message}
 * (FR-001-12, ADR-0027, T-001-38a).
 *
 * <p>
 * All URL expressions evaluate against the <strong>original</strong>
 * (pre-transform) body,
 * not the transformed body (ADR-0027: "route the input, enrich the output").
 *
 * <p>
 * Processing order:
 * <ol>
 * <li>{@code path.expr} → evaluated, produces new request path (string)</li>
 * <li>{@code query.remove} → strip matching query params (glob patterns)</li>
 * <li>{@code query.add} (static) → set params with literal values</li>
 * <li>{@code query.add} (dynamic) → evaluate expr against original body</li>
 * <li>{@code method.when} predicate → if true (or absent), {@code method.set}
 * applied</li>
 * </ol>
 */
public final class UrlTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(UrlTransformer.class);

    private UrlTransformer() {
        // utility class
    }

    /**
     * Applies URL rewrite operations from the given {@link UrlSpec} to the message.
     *
     * @param message      the message to transform URL on
     * @param urlSpec      the URL rewrite operations to apply
     * @param originalBody the body <strong>before</strong> JSLT transform
     *                     (ADR-0027)
     * @param context      the transform context for $headers/$status bindings
     * @return a new Message with modified URL (body/headers/status unchanged)
     * @throws ExpressionEvalException if path.expr returns null or non-string
     */
    public static Message apply(Message message, UrlSpec urlSpec, JsonNode originalBody, TransformContext context) {
        if (urlSpec == null) {
            return message;
        }

        String newPath = message.requestPath();
        String newMethod = message.requestMethod();

        // 1. Path rewrite
        if (urlSpec.hasPathRewrite()) {
            newPath = evaluatePathExpr(urlSpec.pathExpr(), originalBody, context, message.requestPath());
        }

        // 2-4. Query parameter operations (T-001-38b — stub, will be implemented
        // separately)

        // 5. Method override (T-001-38c — stub, will be implemented separately)

        // Build the updated message if anything changed
        if (!java.util.Objects.equals(newPath, message.requestPath())
                || !java.util.Objects.equals(newMethod, message.requestMethod())) {
            return new Message(
                    message.body(),
                    message.headers(),
                    message.headersAll(),
                    message.statusCode(),
                    message.contentType(),
                    newPath,
                    newMethod);
        }

        return message;
    }

    /**
     * Evaluates the path expression against the original body and returns the
     * percent-encoded result.
     *
     * @throws ExpressionEvalException if the expression returns null or non-string
     */
    private static String evaluatePathExpr(
            CompiledExpression pathExpr, JsonNode originalBody, TransformContext context, String currentPath) {
        JsonNode result;
        try {
            result = pathExpr.evaluate(originalBody, context);
        } catch (Exception e) {
            throw new ExpressionEvalException("url.path.expr evaluation failed: " + e.getMessage(), null, null);
        }

        // Null check
        if (result == null || result.isNull() || result.isMissingNode()) {
            throw new ExpressionEvalException("url.path.expr must return a string, got null", null, null);
        }

        // Type check — must be a string
        if (!result.isTextual()) {
            throw new ExpressionEvalException(
                    "url.path.expr must return a string, got " + result.getNodeType(), null, null);
        }

        String rawPath = result.asText();

        // RFC 3986 §3.3: percent-encode path segments, preserving '/' separators
        String encodedPath = percentEncodePath(rawPath);

        LOG.debug("URL path rewrite: {} → {}", currentPath, encodedPath);
        return encodedPath;
    }

    /**
     * Percent-encodes a URL path per RFC 3986 §3.3, preserving '/' separators.
     * Each segment between '/' is individually encoded.
     */
    static String percentEncodePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }

        // Split on '/', encode each segment, rejoin with '/'
        String[] segments = rawPath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(encodePathSegment(segments[i]));
        }
        return sb.toString();
    }

    /**
     * Encodes a single path segment per RFC 3986.
     * Unreserved characters (A-Z, a-z, 0-9, -, ., _, ~) are left as-is.
     * All other characters are percent-encoded.
     */
    private static String encodePathSegment(String segment) {
        if (segment.isEmpty()) {
            return segment;
        }
        // URLEncoder encodes for form data (space→+); we need path encoding (space→%20)
        // Also need to un-encode characters that are safe in path segments
        String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8);
        // URLEncoder encodes spaces as '+', but RFC 3986 requires '%20'
        encoded = encoded.replace("+", "%20");
        // URLEncoder encodes some characters that are safe in path segments
        // Un-encode: - . _ ~ (these are unreserved and should not be encoded)
        // URLEncoder already leaves these unencoded, so no action needed
        return encoded;
    }
}
