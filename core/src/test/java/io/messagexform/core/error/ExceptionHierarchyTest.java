package io.messagexform.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the exception hierarchy (FR-001-07, ADR-0024). Verifies the two-tier structure,
 * common fields, and all concrete exception types.
 */
class ExceptionHierarchyTest {

    // --- Hierarchy structure ---

    @Test
    void transformExceptionIsAbstractAndRoot() {
        assertThat(TransformException.class).isAbstract();
        assertThat(TransformException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }

    @Test
    void transformLoadExceptionIsAbstract() {
        assertThat(TransformLoadException.class).isAbstract();
        assertThat(TransformLoadException.class.getSuperclass()).isEqualTo(TransformException.class);
    }

    @Test
    void transformEvalExceptionIsAbstract() {
        assertThat(TransformEvalException.class).isAbstract();
        assertThat(TransformEvalException.class.getSuperclass()).isEqualTo(TransformException.class);
    }

    // --- Load-time exceptions extend TransformLoadException ---

    @Test
    void specParseExceptionExtendsLoadException() {
        var ex = new SpecParseException("bad yaml", "spec-1", "/path/to/spec.yaml");

        assertThat(ex).isInstanceOf(TransformLoadException.class);
        assertThat(ex).isInstanceOf(TransformException.class);
        assertThat(ex.specId()).isEqualTo("spec-1");
        assertThat(ex.detail()).isEqualTo("bad yaml");
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.LOAD);
        assertThat(ex.source()).isEqualTo("/path/to/spec.yaml");
    }

    @Test
    void expressionCompileExceptionExtendsLoadException() {
        var cause = new RuntimeException("syntax error");
        var ex = new ExpressionCompileException("compile failed", cause, "spec-2", "/spec.yaml");

        assertThat(ex).isInstanceOf(TransformLoadException.class);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.LOAD);
        assertThat(ex.source()).isEqualTo("/spec.yaml");
    }

    @Test
    void schemaValidationExceptionExtendsLoadException() {
        var ex = new SchemaValidationException("invalid schema", "spec-3", "/spec.yaml");

        assertThat(ex).isInstanceOf(TransformLoadException.class);
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.LOAD);
    }

    @Test
    void profileResolveExceptionExtendsLoadException() {
        var ex = new ProfileResolveException("missing spec ref", "spec-4", "/profile.yaml");

        assertThat(ex).isInstanceOf(TransformLoadException.class);
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.LOAD);
    }

    @Test
    void sensitivePathSyntaxErrorExtendsLoadException() {
        var ex = new SensitivePathSyntaxError("invalid RFC 9535 path", "spec-5", "/spec.yaml");

        assertThat(ex).isInstanceOf(TransformLoadException.class);
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.LOAD);
    }

    // --- Eval-time exceptions extend TransformEvalException ---

    @Test
    void expressionEvalExceptionExtendsEvalException() {
        var ex = new ExpressionEvalException("undefined variable", "spec-6", 2);

        assertThat(ex).isInstanceOf(TransformEvalException.class);
        assertThat(ex).isInstanceOf(TransformException.class);
        assertThat(ex.specId()).isEqualTo("spec-6");
        assertThat(ex.detail()).isEqualTo("undefined variable");
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.EVALUATION);
        assertThat(ex.chainStep()).isEqualTo(2);
    }

    @Test
    void evalBudgetExceededExceptionExtendsEvalException() {
        var ex = new EvalBudgetExceededException("timeout: 200ms > 50ms", "spec-7", null);

        assertThat(ex).isInstanceOf(TransformEvalException.class);
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.EVALUATION);
        assertThat(ex.chainStep()).isNull();
    }

    @Test
    void inputSchemaViolationExtendsEvalException() {
        var ex = new InputSchemaViolation("missing required field 'email'", "spec-8", 1);

        assertThat(ex).isInstanceOf(TransformEvalException.class);
        assertThat(ex.phase()).isEqualTo(TransformException.Phase.EVALUATION);
        assertThat(ex.chainStep()).isEqualTo(1);
    }

    // --- URN constants ---

    @Test
    void evalExceptionUrnsMatchSpec() {
        assertThat(ExpressionEvalException.URN).isEqualTo("urn:message-xform:error:expression-eval-failed");
        assertThat(EvalBudgetExceededException.URN).isEqualTo("urn:message-xform:error:eval-budget-exceeded");
        assertThat(InputSchemaViolation.URN).isEqualTo("urn:message-xform:error:schema-validation-failed");
    }

    // --- Common fields ---

    @Test
    void loadExceptionSourceFieldIsAccessible() {
        var ex = new SpecParseException("msg", null, "/some/path.yaml");
        assertThat(ex.source()).isEqualTo("/some/path.yaml");
        assertThat(ex.specId()).isNull();
    }

    @Test
    void evalExceptionChainStepFieldIsAccessible() {
        var ex = new ExpressionEvalException("msg", "spec-id", 3);
        assertThat(ex.chainStep()).isEqualTo(3);
    }

    @Test
    void exceptionWithCausePreservesCauseChain() {
        var root = new IllegalArgumentException("root cause");
        var ex = new ExpressionEvalException("failed", root, "spec-id", null);

        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getMessage()).isEqualTo("failed");
    }
}
