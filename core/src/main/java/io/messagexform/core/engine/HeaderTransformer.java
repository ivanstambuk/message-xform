package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.model.HeaderSpec;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.spi.CompiledExpression;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies declarative header operations (add/remove/rename) to a
 * {@link Message}
 * (FR-001-10, T-001-34, T-001-35).
 *
 * <p>
 * Processing order (per spec):
 * <ol>
 * <li>{@code remove} — strip matching headers (glob patterns)</li>
 * <li>{@code rename} — rename header keys</li>
 * <li>{@code add} (static) — set headers with literal string values</li>
 * <li>{@code add} (dynamic) — evaluate {@code expr} against the transformed
 * body</li>
 * </ol>
 *
 * <p>
 * All header name comparisons are case-insensitive (RFC 9110 §5.1). Header
 * names
 * in the output are always lowercase.
 */
public final class HeaderTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(HeaderTransformer.class);

    private HeaderTransformer() {
        // utility class
    }

    /**
     * Applies header operations from the given {@link HeaderSpec} to the message,
     * returning a new {@link Message} with the modified headers.
     *
     * @param message         the message to transform headers on
     * @param headerSpec      the header operations to apply
     * @param transformedBody the body after JSLT transform (for dynamic expr
     *                        evaluation)
     * @return a new Message with modified headers (body/status/etc. unchanged)
     */
    public static Message apply(Message message, HeaderSpec headerSpec, JsonNode transformedBody) {
        if (headerSpec == null || headerSpec.isEmpty()) {
            return message;
        }

        // Start with mutable copies of the headers
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        message.headersAll().forEach((k, v) -> headersAll.put(k, new ArrayList<>(v)));

        // 1. Remove — glob pattern matching
        if (!headerSpec.remove().isEmpty()) {
            applyRemove(headers, headersAll, headerSpec.remove());
        }

        // 2. Rename — old name → new name
        if (!headerSpec.rename().isEmpty()) {
            applyRename(headers, headersAll, headerSpec.rename());
        }

        // 3. Add (static) — literal string values
        if (!headerSpec.staticAdd().isEmpty()) {
            applyStaticAdd(headers, headersAll, headerSpec.staticAdd());
        }

        // 4. Add (dynamic) — evaluate expr against transformed body (T-001-35)
        if (!headerSpec.dynamicAdd().isEmpty()) {
            applyDynamicAdd(headers, headersAll, headerSpec.dynamicAdd(), transformedBody);
        }

        return new Message(
                message.body(),
                headers,
                headersAll,
                message.statusCode(),
                message.contentType(),
                message.requestPath(),
                message.requestMethod(),
                message.queryString());
    }

    // --- Private helpers ---

    private static void applyRemove(
            Map<String, String> headers, Map<String, List<String>> headersAll, List<String> patterns) {
        for (String pattern : patterns) {
            String normalizedPattern = pattern.toLowerCase();
            // Collect keys to remove (avoid ConcurrentModificationException)
            List<String> toRemove = headers.keySet().stream()
                    .filter(key -> globMatches(normalizedPattern, key.toLowerCase()))
                    .toList();
            for (String key : toRemove) {
                LOG.debug("Removing header: {}", key);
                headers.remove(key);
                headersAll.remove(key);
            }
        }
    }

    private static void applyRename(
            Map<String, String> headers, Map<String, List<String>> headersAll, Map<String, String> renames) {
        for (Map.Entry<String, String> entry : renames.entrySet()) {
            String oldName = entry.getKey().toLowerCase();
            String newName = entry.getValue().toLowerCase();
            if (headers.containsKey(oldName)) {
                String value = headers.remove(oldName);
                headers.put(newName, value);
                LOG.debug("Renamed header: {} → {}", oldName, newName);
            }
            if (headersAll.containsKey(oldName)) {
                List<String> values = headersAll.remove(oldName);
                headersAll.put(newName, values);
            }
        }
    }

    private static void applyStaticAdd(
            Map<String, String> headers, Map<String, List<String>> headersAll, Map<String, String> staticAdd) {
        for (Map.Entry<String, String> entry : staticAdd.entrySet()) {
            String name = entry.getKey().toLowerCase();
            String value = entry.getValue();
            headers.put(name, value);
            headersAll.put(name, List.of(value));
            LOG.debug("Added header: {} = {}", name, value);
        }
    }

    /**
     * Evaluates dynamic header expressions against the transformed body.
     * Non-string results are coerced to their text representation.
     * Null results skip the header (it is not set).
     */
    private static void applyDynamicAdd(
            Map<String, String> headers,
            Map<String, List<String>> headersAll,
            Map<String, CompiledExpression> dynamicAdd,
            JsonNode transformedBody) {
        for (Map.Entry<String, CompiledExpression> entry : dynamicAdd.entrySet()) {
            String name = entry.getKey().toLowerCase();
            CompiledExpression expr = entry.getValue();
            JsonNode result = expr.evaluate(transformedBody, TransformContext.empty());
            if (result == null || result.isNull()) {
                LOG.debug("Dynamic header {} evaluated to null — skipping", name);
                continue;
            }
            // Coerce to string: text nodes return asText(), others return JSON
            // representation
            String value = result.isTextual() ? result.asText() : result.asText();
            headers.put(name, value);
            headersAll.put(name, List.of(value));
            LOG.debug("Added dynamic header: {} = {}", name, value);
        }
    }

    /**
     * Simple glob matching supporting only the {@code *} wildcard.
     * Matches case-insensitively (both pattern and text should be pre-lowercased).
     */
    static boolean globMatches(String pattern, String text) {
        // Convert glob to regex: escape regex chars, replace * with .*
        String regex = pattern.replace(".", "\\.")
                .replace("?", "\\?")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("+", "\\+")
                .replace("|", "\\|")
                .replace("*", ".*");
        return text.matches(regex);
    }
}
