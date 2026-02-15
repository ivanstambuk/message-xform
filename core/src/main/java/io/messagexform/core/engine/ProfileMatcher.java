package io.messagexform.core.engine;

import io.messagexform.core.model.Direction;
import io.messagexform.core.model.ProfileEntry;
import io.messagexform.core.model.TransformProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Matches incoming requests against profile entries (FR-001-05, ADR-0006).
 * Implements "most-specific-wins" resolution: the entry with the highest
 * specificity score (literal path segments) is selected. Ties are broken
 * by constraint count (method, content-type).
 *
 * <p>
 * Thread-safe and stateless — can be shared across threads.
 */
public final class ProfileMatcher {

    private ProfileMatcher() {}

    /**
     * Finds all matching profile entries for the given request parameters,
     * direction, and optional status code, sorted by specificity (highest
     * first).
     *
     * @param profile     the profile to search
     * @param requestPath the request path (e.g. "/json/alpha/authenticate")
     * @param method      the HTTP method (e.g. "POST"), or null
     * @param contentType the Content-Type header value, or null
     * @param direction   the transform direction (REQUEST or RESPONSE)
     * @param statusCode  the HTTP status code for response-direction matching
     *                    (FR-001-15, ADR-0036), or null for request-direction
     *                    or when status is unknown
     * @return list of matching entries sorted by specificity (highest first),
     *         empty if no entries match
     */
    public static List<ProfileEntry> findMatches(
            TransformProfile profile,
            String requestPath,
            String method,
            String contentType,
            Direction direction,
            Integer statusCode) {

        List<ProfileEntry> matches = new ArrayList<>();
        for (ProfileEntry entry : profile.entries()) {
            if (matches(entry, requestPath, method, contentType, direction, statusCode)) {
                matches.add(entry);
            }
        }

        // Sort by specificity (highest first), then by constraint count (highest first)
        matches.sort(Comparator.comparingInt(ProfileEntry::specificityScore)
                .thenComparingInt(ProfileEntry::constraintCount)
                .reversed());

        return matches;
    }

    /**
     * Backward-compatible overload — delegates to the 6-arg version with
     * {@code null} status code.
     */
    public static List<ProfileEntry> findMatches(
            TransformProfile profile, String requestPath, String method, String contentType, Direction direction) {
        return findMatches(profile, requestPath, method, contentType, direction, null);
    }

    /**
     * Finds the single best-matching entry (most-specific-wins), or null
     * if no entries match.
     *
     * @param profile     the profile to search
     * @param requestPath the request path
     * @param method      the HTTP method, or null
     * @param contentType the Content-Type header value, or null
     * @param direction   the transform direction
     * @param statusCode  the HTTP status code, or null
     * @return the best matching entry, or null if no entries match
     */
    public static ProfileEntry findBestMatch(
            TransformProfile profile,
            String requestPath,
            String method,
            String contentType,
            Direction direction,
            Integer statusCode) {
        List<ProfileEntry> matches = findMatches(profile, requestPath, method, contentType, direction, statusCode);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Backward-compatible overload — delegates with {@code null} status code.
     */
    public static ProfileEntry findBestMatch(
            TransformProfile profile, String requestPath, String method, String contentType, Direction direction) {
        return findBestMatch(profile, requestPath, method, contentType, direction, null);
    }

    /**
     * Tests whether a single entry matches the given request parameters
     * and optional status code.
     */
    static boolean matches(
            ProfileEntry entry,
            String requestPath,
            String method,
            String contentType,
            Direction direction,
            Integer statusCode) {

        // Direction must match
        if (entry.direction() != direction) {
            return false;
        }

        // Path must match (exact or glob)
        if (!pathMatches(entry.pathPattern(), requestPath)) {
            return false;
        }

        // Method must match (if specified in the entry)
        if (entry.method() != null && method != null && !entry.method().equalsIgnoreCase(method)) {
            return false;
        }

        // Content-type must match (if specified in the entry)
        if (entry.contentType() != null
                && contentType != null
                && !entry.contentType().equalsIgnoreCase(contentType)) {
            return false;
        }

        // Status code must match (if specified in the entry) — FR-001-15, ADR-0036
        // Evaluation order: direction → path → method → content-type → status
        if (entry.statusPattern() != null) {
            if (statusCode == null) {
                // Response direction but no status code on message — defensive:
                // treat as non-matching. This should not happen in practice
                // (adapters always populate statusCode on response Messages).
                return false;
            }
            if (!entry.statusPattern().matches(statusCode)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Backward-compatible overload — delegates with {@code null} status code.
     */
    static boolean matches(
            ProfileEntry entry, String requestPath, String method, String contentType, Direction direction) {
        return matches(entry, requestPath, method, contentType, direction, null);
    }

    /**
     * Matches a request path against a pattern that may contain glob wildcards.
     * Supports:
     * <ul>
     * <li>Exact match: "/json/alpha/authenticate"</li>
     * <li>Single-segment wildcard: "/json/&#42;/authenticate" matches
     * "/json/anything/authenticate"</li>
     * <li>Trailing wildcard: "/json/&#42;" matches "/json/alpha",
     * "/json/bravo"</li>
     * <li>Double wildcard: "/json/&#42;&#42;" matches any path starting with
     * "/json/"</li>
     * </ul>
     */
    static boolean pathMatches(String pattern, String path) {
        if (pattern.equals(path)) {
            return true; // Exact match fast path
        }

        String[] patternParts = splitPath(pattern);
        String[] pathParts = splitPath(path);

        return matchSegments(patternParts, pathParts, 0, 0);
    }

    private static boolean matchSegments(String[] pattern, String[] path, int pi, int si) {
        // Both exhausted → match
        if (pi == pattern.length && si == path.length) {
            return true;
        }

        // Pattern exhausted but path continues → no match
        if (pi == pattern.length) {
            return false;
        }

        // Double wildcard (**) matches zero or more remaining segments
        if (pattern[pi].equals("**")) {
            // Try matching the rest of the pattern at every position
            for (int i = si; i <= path.length; i++) {
                if (matchSegments(pattern, path, pi + 1, i)) {
                    return true;
                }
            }
            return false;
        }

        // Path exhausted but pattern continues → no match (unless remaining is **)
        if (si == path.length) {
            return false;
        }

        // Single wildcard (*) matches exactly one segment
        if (pattern[pi].equals("*")) {
            return matchSegments(pattern, path, pi + 1, si + 1);
        }

        // Literal segment — must match exactly
        if (pattern[pi].equals(path[si])) {
            return matchSegments(pattern, path, pi + 1, si + 1);
        }

        return false;
    }

    private static String[] splitPath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return new String[0];
        }
        // Remove leading slash and split
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return normalized.split("/");
    }
}
