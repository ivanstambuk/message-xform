package io.messagexform.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.error.ProfileResolveException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.StatusPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the {@code match.status} field from a profile entry into a
 * {@link StatusPattern} (FR-001-15, ADR-0036).
 *
 * <p>
 * Handles multiple YAML representations:
 * <ul>
 * <li>Integer node: {@code status: 404} (YAML unquoted integer)
 * <li>String node: {@code status: "404"}, {@code status: "4xx"},
 * {@code status: "400-499"}, {@code status: "!404"}, {@code status: "!5xx"}
 * <li>Array node: {@code status: [200, 201]} or {@code status: ["2xx", 404]}
 * </ul>
 *
 * <p>
 * <strong>YAML gotcha:</strong> Unquoted integers like {@code status: 404}
 * produce a Jackson {@code IntNode}, not a {@code TextNode}. This parser
 * handles both via {@link #parseStatusField}, avoiding the silent failure
 * that would occur with {@code optionalString()}.
 *
 * <p>
 * Thread-safe and stateless — all methods are static.
 */
public final class StatusPatternParser {

    private static final Logger LOG = LoggerFactory.getLogger(StatusPatternParser.class);

    /** Pattern for class shorthand: "2xx", "4xx", etc. */
    private static final Pattern CLASS_PATTERN = Pattern.compile("^([1-5])xx$", Pattern.CASE_INSENSITIVE);

    /** Pattern for range: "400-499", "200-204", etc. */
    private static final Pattern RANGE_PATTERN = Pattern.compile("^(\\d{3})-(\\d{3})$");

    /** Pattern for negation: "!404", "!5xx", etc. */
    private static final Pattern NEGATION_PATTERN = Pattern.compile("^!(.+)$");

    /** Pattern for exact code string: "200", "404", etc. */
    private static final Pattern EXACT_PATTERN = Pattern.compile("^\\d{3}$");

    private StatusPatternParser() {}

    /**
     * Parses the {@code status} field from a {@code match} node into a
     * {@link StatusPattern}, or returns {@code null} if no status field is
     * present.
     *
     * <p>
     * Validates that {@code match.status} is only used on response-direction
     * entries (rejects request-direction at load time per ADR-0036).
     *
     * @param matchNode the parsed match block from the profile entry
     * @param direction the direction of this entry
     * @param profileId the profile identifier (for error messages)
     * @param index     the entry index (for error messages)
     * @param source    the source file path (for error messages)
     * @return a parsed StatusPattern, or null if no status field is present
     * @throws ProfileResolveException if the status field is invalid or used
     *                                 on a request-direction entry
     */
    public static StatusPattern parseStatusField(
            JsonNode matchNode, Direction direction, String profileId, int index, String source) {

        JsonNode statusNode = matchNode.get("status");
        if (statusNode == null || statusNode.isNull()) {
            return null; // No status constraint — match any status
        }

        // Validate: status matching is response-only (ADR-0036, S-001-90)
        if (direction == Direction.REQUEST) {
            throw new ProfileResolveException(
                    String.format(
                            "Profile '%s' entry[%d]: match.status is only valid for response-direction entries",
                            profileId, index),
                    null,
                    source);
        }

        return parseNode(statusNode, profileId, index, source);
    }

    /**
     * Parses a single status node (integer, string, or array) into a
     * StatusPattern.
     */
    private static StatusPattern parseNode(JsonNode node, String profileId, int index, String source) {
        // YAML integer: status: 404 (unquoted)
        if (node.isInt() || node.isNumber()) {
            int code = node.asInt();
            return createExact(code, profileId, index, source);
        }

        // YAML string: "404", "4xx", "400-499", "!404", "!5xx"
        if (node.isTextual()) {
            return parseString(node.asText().trim(), profileId, index, source);
        }

        // YAML array: [200, 201] or ["2xx", 404]
        if (node.isArray()) {
            if (node.isEmpty()) {
                throw new ProfileResolveException(
                        String.format("Profile '%s' entry[%d]: match.status array must not be empty", profileId, index),
                        null,
                        source);
            }
            List<StatusPattern> patterns = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                patterns.add(parseNode(node.get(i), profileId, index, source));
            }
            // Single-element array → unwrap to the inner pattern
            if (patterns.size() == 1) {
                return patterns.get(0);
            }
            return new StatusPattern.AnyOf(patterns);
        }

        throw new ProfileResolveException(
                String.format(
                        "Profile '%s' entry[%d]: match.status must be an integer, string, or array, got: %s",
                        profileId, index, node.getNodeType()),
                null,
                source);
    }

    /**
     * Parses a status pattern from a string value.
     */
    private static StatusPattern parseString(String value, String profileId, int index, String source) {
        if (value.isEmpty()) {
            throw new ProfileResolveException(
                    String.format("Profile '%s' entry[%d]: match.status string must not be empty", profileId, index),
                    null,
                    source);
        }

        // Negation: "!404", "!5xx"
        Matcher negMatch = NEGATION_PATTERN.matcher(value);
        if (negMatch.matches()) {
            String inner = negMatch.group(1);
            StatusPattern innerPattern = parseString(inner, profileId, index, source);
            return new StatusPattern.Not(innerPattern);
        }

        // Range: "400-499"
        Matcher rangeMatch = RANGE_PATTERN.matcher(value);
        if (rangeMatch.matches()) {
            int low = Integer.parseInt(rangeMatch.group(1));
            int high = Integer.parseInt(rangeMatch.group(2));
            validateCodeRange(low, profileId, index, source);
            validateCodeRange(high, profileId, index, source);
            if (low > high) {
                throw new ProfileResolveException(
                        String.format(
                                "Profile '%s' entry[%d]: match.status range lower bound (%d) must not exceed upper bound (%d)",
                                profileId, index, low, high),
                        null,
                        source);
            }
            if (low == high) {
                LOG.warn(
                        "Profile '{}' entry[{}]: match.status range {}-{} has equal bounds — consider using exact match '{}'",
                        profileId,
                        index,
                        low,
                        high,
                        low);
            }
            return new StatusPattern.Range(low, high);
        }

        // Class: "2xx", "4xx"
        Matcher classMatch = CLASS_PATTERN.matcher(value);
        if (classMatch.matches()) {
            int classDigit = Integer.parseInt(classMatch.group(1));
            return new StatusPattern.Class(classDigit);
        }

        // Exact code: "200", "404"
        if (EXACT_PATTERN.matcher(value).matches()) {
            int code = Integer.parseInt(value);
            return createExact(code, profileId, index, source);
        }

        throw new ProfileResolveException(
                String.format(
                        "Profile '%s' entry[%d]: invalid match.status pattern '%s' — expected exact code (e.g. 404),"
                                + " class (e.g. 4xx), range (e.g. 400-499), or negation (e.g. !404)",
                        profileId, index, value),
                null,
                source);
    }

    private static StatusPattern.Exact createExact(int code, String profileId, int index, String source) {
        validateCodeRange(code, profileId, index, source);
        return new StatusPattern.Exact(code);
    }

    private static void validateCodeRange(int code, String profileId, int index, String source) {
        if (code < 100 || code > 599) {
            throw new ProfileResolveException(
                    String.format(
                            "Profile '%s' entry[%d]: match.status code %d is outside valid range (100–599)",
                            profileId, index, code),
                    null,
                    source);
        }
    }
}
