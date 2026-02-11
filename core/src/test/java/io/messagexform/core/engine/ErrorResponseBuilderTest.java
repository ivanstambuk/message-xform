package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.error.EvalBudgetExceededException;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.error.InputSchemaViolation;
import io.messagexform.core.testkit.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ErrorResponseBuilder} (T-001-22, FR-001-07, CFG-001-03/04).
 * Verifies that evaluation exceptions are translated into RFC 9457 Problem
 * Details JSON responses.
 */
@DisplayName("T-001-22: Error response builder — RFC 9457")
class ErrorResponseBuilderTest {

    private ErrorResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ErrorResponseBuilder();
    }

    @Nested
    @DisplayName("ExpressionEvalException → RFC 9457")
    class ExpressionEvalTests {

        @Test
        @DisplayName("type matches URN from Error Catalogue")
        void type_matchesUrn() {
            ExpressionEvalException ex =
                    new ExpressionEvalException("undefined variable 'foo'", "callback-prettify@1.0.0", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/json/alpha/authenticate"));

            assertThat(response.get("type").asText()).isEqualTo(ExpressionEvalException.URN);
            assertThat(response.get("type").asText()).isEqualTo("urn:message-xform:error:expression-eval-failed");
        }

        @Test
        @DisplayName("title is 'Transform Failed'")
        void title_isTransformFailed() {
            ExpressionEvalException ex = new ExpressionEvalException("some error", "spec-1", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("title").asText()).isEqualTo("Transform Failed");
        }

        @Test
        @DisplayName("status defaults to 502")
        void status_defaultsTo502() {
            ExpressionEvalException ex = new ExpressionEvalException("some error", "spec-1", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("status").asInt()).isEqualTo(502);
        }

        @Test
        @DisplayName("detail contains exception message")
        void detail_containsMessage() {
            ExpressionEvalException ex = new ExpressionEvalException(
                    "JSLT evaluation error: undefined variable at line 3", "callback-prettify@1.0.0", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("detail").asText()).contains("JSLT evaluation error");
            assertThat(response.get("detail").asText()).contains("undefined variable");
        }

        @Test
        @DisplayName("instance is set to request path")
        void instance_isRequestPath() {
            ExpressionEvalException ex = new ExpressionEvalException("error", "spec-1", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/json/alpha/authenticate"));

            assertThat(response.get("instance").asText()).isEqualTo("/json/alpha/authenticate");
        }
    }

    @Nested
    @DisplayName("EvalBudgetExceededException → RFC 9457")
    class BudgetExceededTests {

        @Test
        @DisplayName("type matches budget-exceeded URN")
        void type_matchesBudgetUrn() {
            EvalBudgetExceededException ex =
                    new EvalBudgetExceededException("evaluation exceeded 50ms budget", "slow-spec", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/slow"));

            assertThat(response.get("type").asText()).isEqualTo(EvalBudgetExceededException.URN);
            assertThat(response.get("type").asText()).isEqualTo("urn:message-xform:error:eval-budget-exceeded");
        }
    }

    @Nested
    @DisplayName("InputSchemaViolation → RFC 9457")
    class SchemaViolationTests {

        @Test
        @DisplayName("type matches schema-validation URN")
        void type_matchesSchemaUrn() {
            InputSchemaViolation ex =
                    new InputSchemaViolation("missing required field: authId", "callback-prettify", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/validate"));

            assertThat(response.get("type").asText()).isEqualTo(InputSchemaViolation.URN);
            assertThat(response.get("type").asText()).isEqualTo("urn:message-xform:error:schema-validation-failed");
        }
    }

    @Nested
    @DisplayName("Configurable status code")
    class ConfigurableStatus {

        @Test
        @DisplayName("custom status overrides default 502")
        void customStatus_overridesDefault() {
            ErrorResponseBuilder customBuilder = new ErrorResponseBuilder(500);

            ExpressionEvalException ex = new ExpressionEvalException("error", "spec-1", null);
            JsonNode response = TestMessages.parseBody(customBuilder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("status").asInt()).isEqualTo(500);
        }

        @Test
        @DisplayName("status 503 for budget exceeded with custom builder")
        void status503_forBudgetExceeded() {
            ErrorResponseBuilder customBuilder = new ErrorResponseBuilder(503);

            EvalBudgetExceededException ex = new EvalBudgetExceededException("timeout", "slow-spec", null);
            JsonNode response = TestMessages.parseBody(customBuilder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.get("status").asInt()).isEqualTo(503);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null instance path produces null node for instance")
        void nullInstance_producesNullNode() {
            ExpressionEvalException ex = new ExpressionEvalException("error", "spec-1", null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, null));

            assertThat(response.get("instance").isNull()).isTrue();
        }

        @Test
        @DisplayName("null specId in exception is handled gracefully")
        void nullSpecId_handledGracefully() {
            ExpressionEvalException ex = new ExpressionEvalException("error", null, null);

            JsonNode response = TestMessages.parseBody(builder.buildErrorResponse(ex, "/api/test"));

            assertThat(response.has("detail")).isTrue();
            assertThat(response.get("type").asText()).isEqualTo(ExpressionEvalException.URN);
        }
    }
}
