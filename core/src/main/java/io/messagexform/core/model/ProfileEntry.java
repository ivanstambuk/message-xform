package io.messagexform.core.model;

import java.util.Objects;

/**
 * A single transform binding within a {@link TransformProfile} (DO-001-08).
 * Binds a {@link TransformSpec} to a request match pattern (path, method,
 * content-type) and direction.
 *
 * <p>
 * Immutable, thread-safe — created at profile load time.
 *
 * @param spec        the resolved transform spec
 * @param direction   request or response direction
 * @param pathPattern the URL path pattern (exact or glob, e.g.
 *                    "/json/&ast;/authenticate")
 * @param method      HTTP method filter (e.g. "POST"), or null for any method
 * @param contentType content-type filter (e.g. "application/json"), or null for
 *                    any
 */
public record ProfileEntry(
        TransformSpec spec, Direction direction, String pathPattern, String method, String contentType) {

    /** Canonical constructor — validates required fields. */
    public ProfileEntry {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(pathPattern, "pathPattern must not be null");
    }

    /**
     * Computes the specificity score for this entry's path pattern (ADR-0006).
     * The score is the count of literal (non-wildcard) segments. Higher = more
     * specific.
     *
     * <p>
     * Examples:
     * <ul>
     * <li>"/json/alpha/authenticate" → 3 (all literal)</li>
     * <li>"/json/&ast;/authenticate" → 2 (one wildcard)</li>
     * <li>"/json/&ast;" → 1 (one wildcard)</li>
     * <li>"/**" → 0 (all wildcards — should never happen in practice)</li>
     * </ul>
     */
    public int specificityScore() {
        if (pathPattern.isEmpty() || pathPattern.equals("/")) {
            return 0;
        }
        String[] segments = pathPattern.split("/");
        int score = 0;
        for (String segment : segments) {
            if (!segment.isEmpty() && !segment.equals("*") && !segment.equals("**")) {
                score++;
            }
        }
        return score;
    }

    /**
     * Returns the number of match constraints (method + content-type) for
     * tie-breaking (ADR-0006).
     */
    public int constraintCount() {
        int count = 0;
        if (method != null) count++;
        if (contentType != null) count++;
        return count;
    }
}
