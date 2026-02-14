package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.pingidentity.pa.sdk.http.ExchangeProperty;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for ExchangeProperty declarations and TransformResultSummary
 * (T-002-15a, FR-002-07).
 */
class ExchangePropertyTest {

    // ---- T-002-15a: ExchangeProperty declarations ----

    @Nested
    class PropertyDeclarations {

        @Test
        void transformDeniedPropertyIsNotNull() {
            ExchangeProperty<Boolean> prop = MessageTransformRule.TRANSFORM_DENIED;
            assertThat(prop).isNotNull();
        }

        @Test
        void transformDeniedPropertyNamespaceIsCorrect() {
            assertThat(MessageTransformRule.TRANSFORM_DENIED.getNamespace()).isEqualTo("io.messagexform");
        }

        @Test
        void transformDeniedPropertyIdentifierIsCorrect() {
            assertThat(MessageTransformRule.TRANSFORM_DENIED.getIdentifier()).isEqualTo("transformDenied");
        }

        @Test
        void transformDeniedPropertyTypeIsBoolean() {
            assertThat(MessageTransformRule.TRANSFORM_DENIED.getType()).isEqualTo(Boolean.class);
        }

        @Test
        void transformResultPropertyIsNotNull() {
            ExchangeProperty<TransformResultSummary> prop = MessageTransformRule.TRANSFORM_RESULT;
            assertThat(prop).isNotNull();
        }

        @Test
        void transformResultPropertyNamespaceIsCorrect() {
            assertThat(MessageTransformRule.TRANSFORM_RESULT.getNamespace()).isEqualTo("io.messagexform");
        }

        @Test
        void transformResultPropertyIdentifierIsCorrect() {
            assertThat(MessageTransformRule.TRANSFORM_RESULT.getIdentifier()).isEqualTo("transformResult");
        }

        @Test
        void transformResultPropertyTypeIsSummary() {
            assertThat(MessageTransformRule.TRANSFORM_RESULT.getType()).isEqualTo(TransformResultSummary.class);
        }

        @Test
        void propertiesHaveDistinctIdentifiers() {
            assertThat(MessageTransformRule.TRANSFORM_DENIED.getIdentifier())
                    .isNotEqualTo(MessageTransformRule.TRANSFORM_RESULT.getIdentifier());
        }
    }

    // ---- T-002-15a: TransformResultSummary record ----

    @Nested
    class ResultSummary {

        @Test
        void successSummary() {
            TransformResultSummary summary =
                    new TransformResultSummary("my-spec", "1.0", "REQUEST", 42L, "SUCCESS", null, null);

            assertThat(summary.specId()).isEqualTo("my-spec");
            assertThat(summary.specVersion()).isEqualTo("1.0");
            assertThat(summary.direction()).isEqualTo("REQUEST");
            assertThat(summary.durationMs()).isEqualTo(42L);
            assertThat(summary.outcome()).isEqualTo("SUCCESS");
            assertThat(summary.errorType()).isNull();
            assertThat(summary.errorMessage()).isNull();
        }

        @Test
        void passthroughSummary() {
            TransformResultSummary summary =
                    new TransformResultSummary(null, null, "REQUEST", 1L, "PASSTHROUGH", null, null);

            assertThat(summary.specId()).isNull();
            assertThat(summary.specVersion()).isNull();
            assertThat(summary.outcome()).isEqualTo("PASSTHROUGH");
        }

        @Test
        void errorSummary() {
            TransformResultSummary summary = new TransformResultSummary(
                    "err-spec", "2.0", "RESPONSE", 100L, "ERROR", "EVAL_FAILED", "Expression evaluation timed out");

            assertThat(summary.specId()).isEqualTo("err-spec");
            assertThat(summary.outcome()).isEqualTo("ERROR");
            assertThat(summary.errorType()).isEqualTo("EVAL_FAILED");
            assertThat(summary.errorMessage()).isEqualTo("Expression evaluation timed out");
        }

        @Test
        void recordEquals() {
            TransformResultSummary a = new TransformResultSummary("s", "1", "REQUEST", 5L, "SUCCESS", null, null);
            TransformResultSummary b = new TransformResultSummary("s", "1", "REQUEST", 5L, "SUCCESS", null, null);
            assertThat(a).isEqualTo(b);
        }
    }
}
