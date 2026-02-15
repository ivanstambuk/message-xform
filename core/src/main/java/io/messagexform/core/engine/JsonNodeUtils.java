package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shared JSON node utility methods (FR-001-11, FR-001-12, FR-001-16).
 *
 * <p>
 * Extracted from duplicated copies in {@link StatusTransformer} and
 * {@link UrlTransformer} to avoid triple-duplication when {@code match.when}
 * predicates need the same logic (ADR-0036, T-001-71).
 *
 * <p>
 * Thread-safe — stateless utility class.
 */
public final class JsonNodeUtils {

    private JsonNodeUtils() {}

    /**
     * Determines if a JsonNode represents a truthy value in JSLT semantics.
     *
     * <ul>
     * <li>{@code null}, {@code NullNode}, {@code MissingNode} → falsy</li>
     * <li>{@code BooleanNode(false)} → falsy</li>
     * <li>{@code BooleanNode(true)} → truthy</li>
     * <li>{@code TextNode("")} → falsy (empty string)</li>
     * <li>Any other non-null node → truthy</li>
     * </ul>
     *
     * @param node the JSLT evaluation result to check
     * @return true if the result is truthy
     */
    public static boolean isTruthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        // Any other non-null value is truthy
        return true;
    }
}
