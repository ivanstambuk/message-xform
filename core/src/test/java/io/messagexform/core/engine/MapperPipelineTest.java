package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for mapper block and apply pipeline (T-001-39, FR-001-08, ADR-0014).
 * Validates the sequential execution of named mappers composed via a
 * declarative
 * {@code apply} list within a single transform spec.
 *
 * <p>
 * Core scenario: S-001-50 — Apply Directive — Mapper + Expr + Mapper Pipeline.
 */
@DisplayName("T-001-39: Mapper block + apply pipeline (FR-001-08, ADR-0014)")
class MapperPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Nested
    @DisplayName("S-001-50: Apply Directive — Mapper + Expr + Mapper Pipeline")
    class ApplyDirectivePipeline {

        @Test
        @DisplayName("strip-internal → expr → add-metadata pipeline produces correct output")
        void fullPipeline_stripsInternalFields_appliesExpr_addsMetadata() throws Exception {
            // S-001-50 scenario: 3-step pipeline
            // Step 1: strip-internal removes _internal_id and _debug_trace
            // Step 2: expr restructures to {orderId, amount, currency}
            // Step 3: add-metadata appends _meta block
            Path specPath = createTempSpec("""
          id: order-transform
          version: "1.0.0"
          input:
            schema:
              type: object
          output:
            schema:
              type: object
          mappers:
            strip-internal:
              lang: jslt
              expr: |
                {
                  "orderId": .orderId,
                  "amount": .amount,
                  "currency": .currency
                }
            add-metadata:
              lang: jslt
              expr: |
                . + {
                  "_meta": {
                    "transformedBy": "message-xform",
                    "specVersion": "1.0.0"
                  }
                }
          transform:
            lang: jslt
            expr: .
            apply:
              - mapperRef: strip-internal
              - expr
              - mapperRef: add-metadata
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("""
          {
            "orderId": "ord-123",
            "amount": 99.50,
            "currency": "EUR",
            "_internal_id": 88412,
            "_debug_trace": "svc=orders,dur=8ms"
          }
          """);
            Message message = new Message(body, null, null, 200, "application/json", "/api/orders", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            JsonNode output = result.message().body();

            // Verify strip-internal removed the unwanted fields
            assertThat(output.has("_internal_id")).isFalse();
            assertThat(output.has("_debug_trace")).isFalse();

            // Verify the main expr preserved the data fields
            assertThat(output.get("orderId").asText()).isEqualTo("ord-123");
            assertThat(output.get("amount").asDouble()).isEqualTo(99.50);
            assertThat(output.get("currency").asText()).isEqualTo("EUR");

            // Verify add-metadata appended the _meta block
            assertThat(output.has("_meta")).isTrue();
            assertThat(output.get("_meta").get("transformedBy").asText()).isEqualTo("message-xform");
            assertThat(output.get("_meta").get("specVersion").asText()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("each step's output feeds the next step's input")
        void stepOutputFeedsNextInput() throws Exception {
            // Step 1: mapper adds field "step1": true
            // Step 2: expr checks that "step1" exists and adds "step2": true
            // Step 3: mapper checks that both exist and adds "step3": true
            Path specPath = createTempSpec("""
          id: chain-verify
          version: "1.0.0"
          input:
            schema:
              type: object
          output:
            schema:
              type: object
          mappers:
            add-step1:
              lang: jslt
              expr: |
                . + { "step1": true }
            add-step3:
              lang: jslt
              expr: |
                . + { "step3": true, "sawStep2": .step2 }
          transform:
            lang: jslt
            expr: |
              . + { "step2": true, "sawStep1": .step1 }
            apply:
              - mapperRef: add-step1
              - expr
              - mapperRef: add-step3
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"initial\": true}");
            Message message = new Message(body, null, null, 200, "application/json", "/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            JsonNode output = result.message().body();

            // All steps executed
            assertThat(output.get("initial").asBoolean()).isTrue();
            assertThat(output.get("step1").asBoolean()).isTrue();
            assertThat(output.get("step2").asBoolean()).isTrue();
            assertThat(output.get("step3").asBoolean()).isTrue();

            // Step 2 (expr) saw step1's output
            assertThat(output.get("sawStep1").asBoolean()).isTrue();
            // Step 3 (add-step3) saw step2's output
            assertThat(output.get("sawStep2").asBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("spec without apply block → normal single-expression evaluation")
        void noApplyBlock_normalEvaluation() throws Exception {
            Path specPath = createTempSpec("""
          id: no-apply
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
              { "result": .data }
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"hello\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().body().get("result").asText()).isEqualTo("hello");
        }

        @Test
        @DisplayName("mappers block without apply → normal single-expression evaluation")
        void mappersWithoutApply_normalEvaluation() throws Exception {
            // Mappers defined but no apply list — should behave as normal
            Path specPath = createTempSpec("""
          id: mappers-no-apply
          version: "1.0.0"
          input:
            schema:
              type: object
          output:
            schema:
              type: object
          mappers:
            unused-mapper:
              lang: jslt
              expr: |
                . + { "unused": true }
          transform:
            lang: jslt
            expr: |
              { "result": .data }
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"hello\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().body().get("result").asText()).isEqualTo("hello");
            // unused mapper should NOT have been applied
            assertThat(result.message().body().has("unused")).isFalse();
        }
    }

    @Nested
    @DisplayName("Single mapper pipelines")
    class SingleMapperPipeline {

        @Test
        @DisplayName("apply with only expr step → uses main expression only")
        void applyWithExprOnly_usesMainExpression() throws Exception {
            Path specPath = createTempSpec("""
          id: expr-only-apply
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
              { "processed": .data }
            apply:
              - expr
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"test\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().body().get("processed").asText()).isEqualTo("test");
        }

        @Test
        @DisplayName("apply with mapper before expr → mapper preprocesses input")
        void mapperBeforeExpr_preprocessesInput() throws Exception {
            // Step 1: mapper wraps name in a prefix
            // Step 2: expr restructures into final greeting form
            Path specPath = createTempSpec("""
          id: pre-process
          version: "1.0.0"
          input:
            schema:
              type: object
          output:
            schema:
              type: object
          mappers:
            wrap-name:
              lang: jslt
              expr: |
                . + { "displayName": "User:" + .name }
          transform:
            lang: jslt
            expr: |
              { "greeting": "Hello, " + .displayName + "!" }
            apply:
              - mapperRef: wrap-name
              - expr
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"name\": \"alice\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            // Mapper added displayName with "User:" prefix, then expr used it
            assertThat(result.message().body().get("greeting").asText()).isEqualTo("Hello, User:alice!");
        }
    }

    @Nested
    @DisplayName("Pipeline with headers and status")
    class PipelineWithDeclarativeOps {

        @Test
        @DisplayName("apply pipeline works alongside header and status transforms")
        void pipelineWithHeadersAndStatus() throws Exception {
            Path specPath = createTempSpec("""
          id: pipeline-with-headers
          version: "1.0.0"
          input:
            schema:
              type: object
          output:
            schema:
              type: object
          mappers:
            enrich:
              lang: jslt
              expr: |
                . + { "enriched": true }
          transform:
            lang: jslt
            expr: |
              { "data": .value, "enriched": .enriched }
            apply:
              - mapperRef: enrich
              - expr
          headers:
            add:
              x-pipeline-applied: "true"
          """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"value\": \"important\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().body().get("data").asText()).isEqualTo("important");
            assertThat(result.message().body().get("enriched").asBoolean()).isTrue();
            // Header also applied
            assertThat(result.message().headers().get("x-pipeline-applied")).isEqualTo("true");
        }
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
