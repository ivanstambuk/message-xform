package io.messagexform.core.engine.jslt;

import com.fasterxml.jackson.databind.JsonNode;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.JsltException;
import com.schibsted.spt.data.jslt.Parser;
import io.messagexform.core.error.ExpressionCompileException;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.spi.CompiledExpression;
import io.messagexform.core.spi.ExpressionEngine;
import java.util.HashMap;
import java.util.Map;

/**
 * JSLT expression engine implementation (FR-001-02, SPI-001-01/02/03). Uses the Schibsted JSLT
 * library to compile and evaluate JSON-to-JSON transforms.
 *
 * <p>Context variables ({@code $headers}, {@code $headers_all}, {@code $status}, {@code
 * $queryParams}, {@code $cookies}) are injected as external JSLT variables.
 */
public final class JsltExpressionEngine implements ExpressionEngine {

    /** Engine identifier used in spec YAML {@code lang:} field. */
    public static final String ENGINE_ID = "jslt";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public CompiledExpression compile(String expression) {
        try {
            Expression jsltExpr = Parser.compileString(expression);
            return new JsltCompiledExpression(jsltExpr);
        } catch (JsltException e) {
            throw new ExpressionCompileException("Failed to compile JSLT expression: " + e.getMessage(), e, null, null);
        }
    }

    /** Thread-safe compiled JSLT expression handle. */
    private static final class JsltCompiledExpression implements CompiledExpression {

        private final Expression jsltExpression;

        JsltCompiledExpression(Expression jsltExpression) {
            this.jsltExpression = jsltExpression;
        }

        @Override
        public JsonNode evaluate(JsonNode input, TransformContext context) {
            try {
                Map<String, JsonNode> variables = buildVariables(context);
                return jsltExpression.apply(variables, input);
            } catch (JsltException e) {
                throw new ExpressionEvalException("JSLT evaluation failed: " + e.getMessage(), e, null, null);
            }
        }

        /**
         * Builds the JSLT external variable map from the transform context. Variables are named to
         * match the spec-defined context variable names.
         */
        private static Map<String, JsonNode> buildVariables(TransformContext context) {
            Map<String, JsonNode> vars = new HashMap<>();
            vars.put("headers", context.headersAsJson());
            vars.put("headers_all", context.headersAllAsJson());
            vars.put("status", context.statusAsJson());
            vars.put("queryParams", context.queryParamsAsJson());
            vars.put("cookies", context.cookiesAsJson());
            return vars;
        }
    }
}
