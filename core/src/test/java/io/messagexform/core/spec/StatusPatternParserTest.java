package io.messagexform.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.messagexform.core.error.ProfileResolveException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.StatusPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StatusPatternParser} (FR-001-15, ADR-0036).
 * Covers string patterns, integer YAML nodes, arrays, validation, and
 * the YAML integer-vs-string gotcha.
 */
@DisplayName("StatusPatternParser")
class StatusPatternParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Helpers ---

    private ObjectNode matchNode(String field, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(field, value);
        return node;
    }

    private ObjectNode matchNodeInt(String field, int value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(field, value);
        return node;
    }

    private ObjectNode matchNodeArray(String field, Object... values) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode arr = node.putArray(field);
        for (Object v : values) {
            if (v instanceof Integer i) {
                arr.add(i);
            } else if (v instanceof String s) {
                arr.add(s);
            }
        }
        return node;
    }

    @Nested
    @DisplayName("No status field")
    class NoStatusField {

        @Test
        @DisplayName("returns null when status field is absent")
        void absentField() {
            ObjectNode matchNode = MAPPER.createObjectNode();
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode, Direction.RESPONSE, "test-profile", 0, "test.yaml");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when status field is null node")
        void nullNode() {
            ObjectNode matchNode = MAPPER.createObjectNode();
            matchNode.putNull("status");
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode, Direction.RESPONSE, "test-profile", 0, "test.yaml");
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Request-direction rejection (S-001-90)")
    class RequestDirectionRejection {

        @Test
        @DisplayName("rejects match.status on request-direction entry")
        void rejectsRequestDirection() {
            ObjectNode matchNode = matchNode("status", "200");
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(
                            matchNode, Direction.REQUEST, "test-profile", 0, "test.yaml"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("only valid for response-direction");
        }
    }

    @Nested
    @DisplayName("String patterns")
    class StringPatterns {

        @Test
        @DisplayName("parses exact code string: \"200\"")
        void exactCodeString() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "200"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Exact.class);
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(201)).isFalse();
        }

        @Test
        @DisplayName("parses exact code string: \"404\"")
        void exactCode404() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "404"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Exact.class);
            assertThat(result.matches(404)).isTrue();
        }

        @Test
        @DisplayName("parses class pattern: \"2xx\"")
        void classPattern2xx() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "2xx"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Class.class);
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(204)).isTrue();
            assertThat(result.matches(300)).isFalse();
        }

        @Test
        @DisplayName("parses class pattern: \"4xx\" (case-insensitive)")
        void classPattern4xxCaseInsensitive() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "4XX"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Class.class);
            assertThat(result.matches(400)).isTrue();
        }

        @Test
        @DisplayName("parses class pattern: \"5xx\"")
        void classPattern5xx() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "5xx"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Class.class);
            assertThat(result.matches(500)).isTrue();
            assertThat(result.matches(502)).isTrue();
            assertThat(result.matches(200)).isFalse();
        }

        @Test
        @DisplayName("parses range pattern: \"400-499\"")
        void rangePattern() {
            StatusPattern result = StatusPatternParser.parseStatusField(
                    matchNode("status", "400-499"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Range.class);
            assertThat(result.matches(400)).isTrue();
            assertThat(result.matches(499)).isTrue();
            assertThat(result.matches(399)).isFalse();
            assertThat(result.matches(500)).isFalse();
        }

        @Test
        @DisplayName("parses narrow range: \"200-204\"")
        void narrowRange() {
            StatusPattern result = StatusPatternParser.parseStatusField(
                    matchNode("status", "200-204"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Range.class);
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(202)).isTrue();
            assertThat(result.matches(204)).isTrue();
            assertThat(result.matches(205)).isFalse();
        }

        @Test
        @DisplayName("parses negation of exact: \"!404\"")
        void negationExact() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "!404"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Not.class);
            assertThat(result.matches(404)).isFalse();
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(500)).isTrue();
        }

        @Test
        @DisplayName("parses negation of class: \"!5xx\"")
        void negationClass() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNode("status", "!5xx"), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Not.class);
            assertThat(result.matches(502)).isFalse();
            assertThat(result.matches(500)).isFalse();
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(404)).isTrue();
        }
    }

    @Nested
    @DisplayName("YAML integer node (S-001-100)")
    class YamlIntegerNode {

        @Test
        @DisplayName("unquoted integer: status: 404 → Exact(404)")
        void integerNodeParsesAsExact() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNodeInt("status", 404), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Exact.class);
            assertThat(result.matches(404)).isTrue();
            assertThat(result.matches(200)).isFalse();
        }

        @Test
        @DisplayName("unquoted integer: status: 200 → Exact(200)")
        void integerNode200() {
            StatusPattern result =
                    StatusPatternParser.parseStatusField(matchNodeInt("status", 200), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.Exact.class);
            assertThat(result.matches(200)).isTrue();
        }

        @Test
        @DisplayName("behaves identically to quoted string")
        void integerBehavesLikeString() {
            StatusPattern fromInt =
                    StatusPatternParser.parseStatusField(matchNodeInt("status", 404), Direction.RESPONSE, "p", 0, "s");
            StatusPattern fromStr =
                    StatusPatternParser.parseStatusField(matchNode("status", "404"), Direction.RESPONSE, "p", 0, "s");
            assertThat(fromInt.matches(404)).isEqualTo(fromStr.matches(404));
            assertThat(fromInt.matches(200)).isEqualTo(fromStr.matches(200));
        }
    }

    @Nested
    @DisplayName("Array patterns")
    class ArrayPatterns {

        @Test
        @DisplayName("parses integer array: [200, 201]")
        void integerArray() {
            StatusPattern result = StatusPatternParser.parseStatusField(
                    matchNodeArray("status", 200, 201), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.AnyOf.class);
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(201)).isTrue();
            assertThat(result.matches(202)).isFalse();
        }

        @Test
        @DisplayName("parses mixed array: [\"2xx\", 404]")
        void mixedArray() {
            StatusPattern result = StatusPatternParser.parseStatusField(
                    matchNodeArray("status", "2xx", 404), Direction.RESPONSE, "p", 0, "s");
            assertThat(result).isInstanceOf(StatusPattern.AnyOf.class);
            assertThat(result.matches(200)).isTrue();
            assertThat(result.matches(204)).isTrue();
            assertThat(result.matches(404)).isTrue();
            assertThat(result.matches(500)).isFalse();
        }

        @Test
        @DisplayName("single-element array unwraps to inner pattern")
        void singleElementArrayUnwraps() {
            StatusPattern result = StatusPatternParser.parseStatusField(
                    matchNodeArray("status", 200), Direction.RESPONSE, "p", 0, "s");
            // Should be unwrapped to Exact, not AnyOf
            assertThat(result).isInstanceOf(StatusPattern.Exact.class);
            assertThat(result.matches(200)).isTrue();
        }

        @Test
        @DisplayName("rejects empty array")
        void rejectsEmptyArray() {
            ObjectNode node = MAPPER.createObjectNode();
            node.putArray("status"); // empty array
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(node, Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("empty");
        }
    }

    @Nested
    @DisplayName("Validation errors")
    class ValidationErrors {

        @Test
        @DisplayName("rejects code outside 100-599 (string)")
        void rejectsInvalidCodeString() {
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(
                            matchNode("status", "999"), Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("outside valid range");
        }

        @Test
        @DisplayName("rejects code outside 100-599 (integer)")
        void rejectsInvalidCodeInt() {
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(
                            matchNodeInt("status", 999), Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("outside valid range");
        }

        @Test
        @DisplayName("rejects invalid pattern syntax")
        void rejectsInvalidSyntax() {
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(
                            matchNode("status", "abc"), Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("invalid match.status pattern");
        }

        @Test
        @DisplayName("rejects range with low > high")
        void rejectsInvertedRange() {
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(
                            matchNode("status", "499-400"), Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("must not exceed upper bound");
        }

        @Test
        @DisplayName("rejects empty string")
        void rejectsEmptyString() {
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(
                            matchNode("status", ""), Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("rejects boolean node type")
        void rejectsBooleanNode() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("status", true);
            assertThatThrownBy(() -> StatusPatternParser.parseStatusField(node, Direction.RESPONSE, "p", 0, "s"))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("must be an integer, string, or array");
        }
    }
}
