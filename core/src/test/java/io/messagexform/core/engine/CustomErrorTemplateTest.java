package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.testkit.TestMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for custom error template support in {@link ErrorResponseBuilder}
 * (T-001-23, FR-001-07, CFG-001-05). Verifies that operator-defined error
 * templates with {@code {{error.*}}} placeholders are correctly substituted.
 */
@DisplayName("T-001-23: Custom error template")
class CustomErrorTemplateTest {

    @Nested
    @DisplayName("Template substitution")
    class TemplateSubstitution {

        @Test
        @DisplayName("{{error.detail}} is substituted with exception message")
        void errorDetail_substitutedWithMessage() {
            String template = """
          {
            "errorCode": "TRANSFORM_FAILED",
            "message": "{{error.detail}}"
          }
          """;
            ErrorResponseBuilder builder = ErrorResponseBuilder.withCustomTemplate(template, 502);

            ExpressionEvalException ex =
                    new ExpressionEvalException("undefined variable 'foo' at line 3", "spec-1", null);
            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("errorCode").asText()).isEqualTo("TRANSFORM_FAILED");
            assertThat(response.get("message").asText()).isEqualTo("undefined variable 'foo' at line 3");
        }

        @Test
        @DisplayName("{{error.specId}} is substituted with spec ID")
        void errorSpecId_substitutedWithSpecId() {
            String template = """
          {
            "spec": "{{error.specId}}",
            "message": "{{error.detail}}"
          }
          """;
            ErrorResponseBuilder builder = ErrorResponseBuilder.withCustomTemplate(template, 502);

            ExpressionEvalException ex = new ExpressionEvalException("some error", "callback-prettify@1.0.0", null);
            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("spec").asText()).isEqualTo("callback-prettify@1.0.0");
        }

        @Test
        @DisplayName("{{error.type}} is substituted with URN")
        void errorType_substitutedWithUrn() {
            String template = """
          {
            "type": "{{error.type}}",
            "message": "{{error.detail}}"
          }
          """;
            ErrorResponseBuilder builder = ErrorResponseBuilder.withCustomTemplate(template, 500);

            ExpressionEvalException ex = new ExpressionEvalException("error", "spec-1", null);
            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("type").asText()).isEqualTo(ExpressionEvalException.URN);
        }

        @Test
        @DisplayName("Multiple placeholders in single template")
        void multiplePlaceholders_allSubstituted() {
            String template = """
          {
            "errorCode": "TRANSFORM_FAILED",
            "message": "{{error.detail}}",
            "specId": "{{error.specId}}",
            "errorType": "{{error.type}}"
          }
          """;
            ErrorResponseBuilder builder = ErrorResponseBuilder.withCustomTemplate(template, 503);

            ExpressionEvalException ex = new ExpressionEvalException("JSLT eval failed", "my-spec@2.0.0", null);
            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("errorCode").asText()).isEqualTo("TRANSFORM_FAILED");
            assertThat(response.get("message").asText()).isEqualTo("JSLT eval failed");
            assertThat(response.get("specId").asText()).isEqualTo("my-spec@2.0.0");
            assertThat(response.get("errorType").asText()).isEqualTo(ExpressionEvalException.URN);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Null specId in exception → 'unknown' placeholder")
        void nullSpecId_substitutedWithUnknown() {
            String template = """
          {
            "spec": "{{error.specId}}"
          }
          """;
            ErrorResponseBuilder builder = ErrorResponseBuilder.withCustomTemplate(template, 502);

            ExpressionEvalException ex = new ExpressionEvalException("error", null, null);
            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("spec").asText()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("Unknown placeholder is left as-is (graceful fallback)")
        void unknownPlaceholder_leftAsIs() {
            String template = """
          {
            "message": "{{error.detail}}",
            "unknown": "{{error.nonExistent}}"
          }
          """;
            ErrorResponseBuilder builder = ErrorResponseBuilder.withCustomTemplate(template, 502);

            ExpressionEvalException ex = new ExpressionEvalException("test error", "spec-1", null);
            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("message").asText()).isEqualTo("test error");
            assertThat(response.get("unknown").asText()).isEqualTo("{{error.nonExistent}}");
        }
    }
}
