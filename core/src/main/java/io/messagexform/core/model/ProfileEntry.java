package io.messagexform.core.model;

import io.messagexform.core.spi.CompiledExpression;
import java.util.Objects;

/**
 * A single transform binding within a {@link TransformProfile} (DO-001-08).
 * Binds a {@link TransformSpec} to a request match pattern (path, method,
 * content-type, status, when predicate) and direction.
 *
 * <p>
 * Immutable, thread-safe — created at profile load time.
 *
 * @param spec          the resolved transform spec
 * @param direction     request or response direction
 * @param pathPattern   the URL path pattern (exact or glob, e.g.
 *                      "/json/&#42;/authenticate")
 * @param method        HTTP method filter (e.g. "POST"), or null for any method
 * @param contentType   content-type filter (e.g. "application/json"), or null
 *                      for any
 * @param statusPattern status code pattern (e.g. exact 404, class 4xx, range
 *                      400-499), or null for any status. Only valid for
 *                      response-direction entries (FR-001-15, ADR-0036).
 * @param whenPredicate compiled body-predicate expression for content-based
 *                      routing (FR-001-16, ADR-0036), or null for "always
 *                      match".
 *                      Evaluated against the parsed JSON body at match time.
 */
public record ProfileEntry(
        TransformSpec spec,
        Direction direction,
        String pathPattern,
        String method,
        String contentType,
        StatusPattern statusPattern,
        CompiledExpression whenPredicate) {

    /** Canonical constructor — validates required fields. */
    public ProfileEntry {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(pathPattern, "pathPattern must not be null");
    }

    /**
     * Backward-compatible constructor without whenPredicate.
     * Equivalent to passing {@code null} for whenPredicate (always match).
     */
    public ProfileEntry(
            TransformSpec spec,
            Direction direction,
            String pathPattern,
            String method,
            String contentType,
            StatusPattern statusPattern) {
        this(spec, direction, pathPattern, method, contentType, statusPattern, null);
    }

    /**
     * Backward-compatible constructor without statusPattern or whenPredicate.
     * Equivalent to passing {@code null} for both (match any status, always match).
     */
    public ProfileEntry(
            TransformSpec spec, Direction direction, String pathPattern, String method, String contentType) {
        this(spec, direction, pathPattern, method, contentType, null, null);
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
     * <li>"/json/&#42;/authenticate" → 2 (one wildcard)</li>
     * <li>"/json/&#42;" → 1 (one wildcard)</li>
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
     * Returns the number of match constraints for tie-breaking (ADR-0006).
     * Includes method, content-type, status pattern (weighted by
     * specificity), and when predicate.
     */
    public int constraintCount() {
        int count = 0;
        if (method != null) count++;
        if (contentType != null) count++;
        if (statusPattern != null) count += statusPattern.specificityWeight();
        if (whenPredicate != null) count++;
        return count;
    }
}
