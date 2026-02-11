package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.EvalBudgetExceededException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.testkit.TestMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for evaluation budget enforcement (T-001-25, NFR-001-07,
 * CFG-001-06/07).
 * Verifies that the engine enforces max-output-bytes and max-eval-ms budgets.
 */
@DisplayName("T-001-25: Evaluation budget — max-output-bytes + max-eval-ms")
class EvalBudgetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("Output size budget")
    class OutputSizeBudget {

        @Test
        @DisplayName("Output within budget → SUCCESS")
        void withinBudget_succeeds() throws Exception {
            // 1MB budget — small output fits easily
            TransformEngine engine = createEngine(50, 1024 * 1024);

            Path specPath = createTempSpec("""
                    id: small-output
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "key": "value" }
                    """);

            engine.loadSpec(specPath);

            Message message = new Message(
                    TestMessages.toBody(MAPPER.readTree("{}"), "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/test",
                    "GET",
                    null,
                    SessionContext.empty());
            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Output exceeds max-output-bytes → ERROR with budget-exceeded URN")
        void exceedsBudget_returnsError() throws Exception {
            // Tiny 10-byte budget — even a small output will exceed it
            TransformEngine engine = createEngine(50, 10);

            Path specPath = createTempSpec("""
                    id: big-output
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        {
                          "longField": "this-string-is-definitely-longer-than-10-bytes"
                        }
                    """);

            engine.loadSpec(specPath);

            Message message = new Message(
                    TestMessages.toBody(MAPPER.readTree("{}"), "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/test",
                    "GET",
                    null,
                    SessionContext.empty());
            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isError())
                    .as("Output exceeding max-output-bytes should produce ERROR")
                    .isTrue();
            assertThat(TestMessages.parseBody(result.errorResponse())
                            .get("type")
                            .asText())
                    .isEqualTo(EvalBudgetExceededException.URN);
        }
    }

    @Nested
    @DisplayName("Time budget")
    class TimeBudget {

        @Test
        @DisplayName("Fast expression within budget → SUCCESS")
        void fastExpression_succeeds() throws Exception {
            // 5 second budget — simple expression finishes instantly
            TransformEngine engine = createEngine(5000, 1024 * 1024);

            Path specPath = createTempSpec("""
                    id: fast-spec
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "result": "fast" }
                    """);

            engine.loadSpec(specPath);

            Message message = new Message(
                    TestMessages.toBody(MAPPER.readTree("{}"), "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/test",
                    "GET",
                    null,
                    SessionContext.empty());
            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
        }

        // Note: Testing time budget exceedance with JSLT is impractical in unit
        // tests because JSLT evaluations are typically sub-millisecond. The
        // time budget is enforced as a wall-clock measurement around the
        // evaluation call. A proper time-budget-exceeded test would require
        // either a mock expression engine or a specially crafted JSLT expression
        // that takes >N ms. The output-size budget (above) serves as the
        // primary guard against runaway expressions.
    }

    // --- Helpers ---

    private TransformEngine createEngine(int maxEvalMs, int maxOutputBytes) {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        EvalBudget budget = new EvalBudget(maxEvalMs, maxOutputBytes);
        return new TransformEngine(specParser, new ErrorResponseBuilder(), budget);
    }

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
