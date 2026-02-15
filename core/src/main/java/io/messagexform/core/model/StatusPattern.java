package io.messagexform.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Value object representing a status-code match pattern (FR-001-15, ADR-0036).
 * Used in {@link ProfileEntry} to filter response-direction entries by HTTP
 * status code.
 *
 * <p>
 * Implementations are a sealed hierarchy — all variants are known at compile
 * time. The {@link #matches(int)} method takes a primitive {@code int};
 * callers <em>must</em> null-check the {@code Integer} status code before
 * calling (see {@link io.messagexform.core.engine.ProfileMatcher}).
 *
 * <p>
 * Thread-safe and immutable.
 */
public sealed interface StatusPattern {

    /**
     * Returns {@code true} if the given status code matches this pattern.
     *
     * @param statusCode the HTTP status code (e.g. 200, 404, 502).
     *                   Must not be called with a null-unboxed Integer —
     *                   callers are responsible for null-checking.
     */
    boolean matches(int statusCode);

    /**
     * Returns the specificity weight for ADR-0006 constraint scoring.
     * Higher weight = more specific. Used by
     * {@link ProfileEntry#constraintCount()}.
     */
    int specificityWeight();

    // ── Implementations ──

    /**
     * Matches a single exact status code (e.g. 404).
     * Specificity weight: 2 (most specific).
     */
    record Exact(int code) implements StatusPattern {
        public Exact {
            if (code < 100 || code > 599) {
                throw new IllegalArgumentException("Status code must be between 100 and 599, got: " + code);
            }
        }

        @Override
        public boolean matches(int statusCode) {
            return statusCode == code;
        }

        @Override
        public int specificityWeight() {
            return 2;
        }
    }

    /**
     * Matches an entire status class (e.g. 4xx matches 400–499).
     * Specificity weight: 1 (less specific than exact).
     *
     * @param classDigit the leading digit (1–5)
     */
    record Class(int classDigit) implements StatusPattern {
        public Class {
            if (classDigit < 1 || classDigit > 5) {
                throw new IllegalArgumentException("Status class digit must be between 1 and 5, got: " + classDigit);
            }
        }

        @Override
        public boolean matches(int statusCode) {
            return statusCode / 100 == classDigit;
        }

        @Override
        public int specificityWeight() {
            return 1;
        }
    }

    /**
     * Matches a contiguous range of status codes, inclusive (e.g. 400–499).
     * Specificity weight: 2 (same as exact — range is an explicit constraint).
     *
     * @param low  range lower bound (inclusive)
     * @param high range upper bound (inclusive)
     */
    record Range(int low, int high) implements StatusPattern {
        public Range {
            if (low < 100 || low > 599) {
                throw new IllegalArgumentException("Range lower bound must be between 100 and 599, got: " + low);
            }
            if (high < 100 || high > 599) {
                throw new IllegalArgumentException("Range upper bound must be between 100 and 599, got: " + high);
            }
            if (low > high) {
                throw new IllegalArgumentException(
                        "Range lower bound must not exceed upper bound: " + low + "-" + high);
            }
        }

        @Override
        public boolean matches(int statusCode) {
            return statusCode >= low && statusCode <= high;
        }

        @Override
        public int specificityWeight() {
            return 2;
        }
    }

    /**
     * Negates an inner pattern (e.g. !404 or !5xx).
     * Specificity weight: 1 (negation is less specific than exact/range).
     *
     * @param inner the pattern to negate
     */
    record Not(StatusPattern inner) implements StatusPattern {
        public Not {
            Objects.requireNonNull(inner, "inner pattern must not be null");
        }

        @Override
        public boolean matches(int statusCode) {
            return !inner.matches(statusCode);
        }

        @Override
        public int specificityWeight() {
            return 1;
        }
    }

    /**
     * Matches if any of the contained patterns match (OR semantics).
     * Specificity weight: max of all contained patterns' weights.
     *
     * @param patterns the list of patterns (must not be empty)
     */
    record AnyOf(List<StatusPattern> patterns) implements StatusPattern {
        public AnyOf {
            Objects.requireNonNull(patterns, "patterns must not be null");
            if (patterns.isEmpty()) {
                throw new IllegalArgumentException("AnyOf requires at least one pattern");
            }
            patterns = List.copyOf(patterns); // defensive immutable copy
        }

        @Override
        public boolean matches(int statusCode) {
            for (StatusPattern pattern : patterns) {
                if (pattern.matches(statusCode)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int specificityWeight() {
            int max = 0;
            for (StatusPattern pattern : patterns) {
                int w = pattern.specificityWeight();
                if (w > max) max = w;
            }
            return max > 0 ? max : 1;
        }
    }
}
