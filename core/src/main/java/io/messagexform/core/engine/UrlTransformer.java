package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.UrlSpec;
import io.messagexform.core.spi.CompiledExpression;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies declarative URL rewrite operations to a {@link Message}
 * (FR-001-12, ADR-0027, T-001-38a/38b).
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
        String newQueryString = message.queryString();

        // 1. Path rewrite
        if (urlSpec.hasPathRewrite()) {
            newPath = evaluatePathExpr(urlSpec.pathExpr(), originalBody, context, message.requestPath());
        }

        // 2-4. Query parameter operations (T-001-38b)
        if (urlSpec.hasQueryOperations()) {
            newQueryString = applyQueryOperations(urlSpec, originalBody, context, message.queryString());
        }

        // 5. Method override (T-001-38c)
        // Evaluates method.when against original body (ADR-0027)
        if (urlSpec.hasMethodOverride()) {
            newMethod = applyMethodOverride(urlSpec, originalBody, context, message.requestMethod());
        }

        // Build the updated message if anything changed
        if (!java.util.Objects.equals(newPath, message.requestPath())
                || !java.util.Objects.equals(newMethod, message.requestMethod())
                || !java.util.Objects.equals(newQueryString, message.queryString())) {
            Message result = message;
            if (!java.util.Objects.equals(newPath, message.requestPath())) {
                result = result.withRequestPath(newPath);
            }
            if (!java.util.Objects.equals(newMethod, message.requestMethod())) {
                result = result.withRequestMethod(newMethod);
            }
            if (!java.util.Objects.equals(newQueryString, message.queryString())) {
                result = result.withQueryString(newQueryString);
            }
            return result;
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

    // --- Query parameter operations (T-001-38b) ---

    /**
     * Applies query parameter remove/add operations and returns the new query
     * string.
     *
     * <p>
     * Processing order per FR-001-12:
     * <ol>
     * <li>{@code query.remove} — strip matching parameters (glob patterns)</li>
     * <li>{@code query.add} (static) — set parameters with literal values</li>
     * <li>{@code query.add} (dynamic) — evaluate expr against original body</li>
     * </ol>
     */
    private static String applyQueryOperations(
            UrlSpec urlSpec, JsonNode originalBody, TransformContext context, String currentQueryString) {
        // Parse existing query string into mutable map (preserves insertion order)
        Map<String, String> params = parseQueryString(currentQueryString);

        // 2. Remove — glob pattern matching (same utility as header remove)
        for (String pattern : urlSpec.queryRemove()) {
            params.entrySet().removeIf(entry -> globMatches(pattern, entry.getKey()));
        }

        // 3. Add (static) — literal values, percent-encoded on output
        for (Map.Entry<String, String> entry : urlSpec.queryStaticAdd().entrySet()) {
            params.put(entry.getKey(), entry.getValue());
        }

        // 4. Add (dynamic) — evaluate expr against ORIGINAL body
        for (Map.Entry<String, CompiledExpression> entry :
                urlSpec.queryDynamicAdd().entrySet()) {
            String paramName = entry.getKey();
            CompiledExpression expr = entry.getValue();
            try {
                JsonNode result = expr.evaluate(originalBody, context);
                if (result != null && !result.isNull() && !result.isMissingNode()) {
                    params.put(paramName, result.asText());
                }
            } catch (Exception e) {
                LOG.warn("url.query.add.{}.expr evaluation failed: {}, skipping", paramName, e.getMessage());
            }
        }

        return buildQueryString(params);
    }

    // --- Method override (T-001-38c) ---

    /**
     * Applies the method override if the {@code when} predicate is satisfied
     * (or absent).
     * The {@code when} predicate evaluates against the <strong>original</strong>
     * body (ADR-0027).
     *
     * @return the new method, or the current method if the predicate is false
     */
    private static String applyMethodOverride(
            UrlSpec urlSpec, JsonNode originalBody, TransformContext context, String currentMethod) {
        // If method.when is present, evaluate it against the original body
        if (urlSpec.methodWhen() != null) {
            try {
                JsonNode result = urlSpec.methodWhen().evaluate(originalBody, context);
                if (!isTruthy(result)) {
                    LOG.debug("url.method.when predicate is false — method unchanged: {}", currentMethod);
                    return currentMethod;
                }
            } catch (Exception e) {
                LOG.warn("url.method.when evaluation failed: {} — method unchanged", e.getMessage());
                return currentMethod;
            }
        }

        // Apply the override
        String newMethod = urlSpec.methodSet();
        LOG.debug("URL method override: {} → {}", currentMethod, newMethod);
        return newMethod;
    }

    /**
     * Checks if a JSLT result is "truthy".
     * Follows JSLT/JSON semantics: null, false, empty string, missing → falsy.
     */
    private static boolean isTruthy(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return false;
        }
        if (result.isBoolean()) {
            return result.booleanValue();
        }
        if (result.isTextual()) {
            return !result.asText().isEmpty();
        }
        // Any other non-null value is truthy
        return true;
    }

    /**
     * Parses a query string (without leading '?') into a map.
     * Returns an empty mutable map if the query string is null or empty.
     */
    static Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                params.put(key, value);
            } else {
                params.put(pair, "");
            }
        }
        return params;
    }

    /**
     * Builds a query string from a map. Returns null if the map is empty.
     * Values are percent-encoded per RFC 3986 §3.4.
     */
    static String buildQueryString(Map<String, String> params) {
        if (params.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(encodeQueryComponent(entry.getKey()));
            if (!entry.getValue().isEmpty()) {
                sb.append('=');
                sb.append(encodeQueryComponent(entry.getValue()));
            }
            first = false;
        }
        return sb.toString();
    }

    /**
     * Matches a glob pattern against a parameter name.
     * Supports '*' as wildcard for zero or more characters.
     * Uses the same approach as {@link HeaderTransformer}.
     */
    static boolean globMatches(String pattern, String text) {
        // Convert glob to regex: escape special chars, replace * with .*
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return text.matches(regex);
    }

    // --- Encoding helpers ---

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
        String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8);
        // URLEncoder encodes spaces as '+', but RFC 3986 requires '%20'
        encoded = encoded.replace("+", "%20");
        return encoded;
    }

    /**
     * Encodes a query component (key or value) per RFC 3986 §3.4.
     * Spaces → %20, other reserved characters percent-encoded.
     */
    private static String encodeQueryComponent(String component) {
        if (component == null || component.isEmpty()) {
            return component;
        }
        String encoded = URLEncoder.encode(component, StandardCharsets.UTF_8);
        // URLEncoder uses + for spaces, RFC 3986 prefers %20 for consistency
        encoded = encoded.replace("+", "%20");
        return encoded;
    }
}
