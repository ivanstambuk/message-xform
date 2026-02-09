package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.spi.CompiledExpression;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for session context binding in JSLT expressions (T-001-55, FR-001-13,
 * ADR-0030).
 *
 * <p>
 * Verifies that {@code $session} is bound as an external JSLT variable
 * from {@link TransformContext#sessionContext()}.
 */
class SessionContextBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsltExpressionEngine engine = new JsltExpressionEngine();

    @Test
    void sessionSubject_injectedIntoBody(/* S-001-82 pattern */ ) throws Exception {
        // JSLT expression that reads $session.sub
        CompiledExpression expr = engine.compile(". + {\"subject\": $session.sub}");

        JsonNode input = MAPPER.readTree("{\"action\": \"login\"}");
        JsonNode session = MAPPER.readTree("{\"sub\": \"bjensen\"}");
        TransformContext ctx = new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), session);

        JsonNode result = expr.evaluate(input, ctx);

        assertThat(result.get("action").asText()).isEqualTo("login");
        assertThat(result.get("subject").asText()).isEqualTo("bjensen");
    }

    @Test
    void sessionRoles_arrayAccess(/* S-001-83 pattern */ ) throws Exception {
        // JSLT expression that reads $session.roles
        CompiledExpression expr = engine.compile("{\"roles\": $session.roles}");

        JsonNode input = MAPPER.readTree("{}");
        JsonNode session = MAPPER.readTree("{\"roles\": [\"admin\", \"user\"]}");
        TransformContext ctx = new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), session);

        JsonNode result = expr.evaluate(input, ctx);

        assertThat(result.get("roles").isArray()).isTrue();
        assertThat(result.get("roles").get(0).asText()).isEqualTo("admin");
        assertThat(result.get("roles").get(1).asText()).isEqualTo("user");
    }

    @Test
    void nullSessionContext_accessReturnsNull(/* S-001-84 pattern */ ) throws Exception {
        // When session context is null, $session should be JSON null.
        // JSLT absent-field behavior: $session.sub evaluates to absent → field omitted
        // from object constructor. Use if-present guard to test.
        CompiledExpression expr = engine.compile(
                "if ($session.sub) . + {\"subject\": $session.sub} else . + {\"subject_present\": false}");

        JsonNode input = MAPPER.readTree("{}");
        // No session context (null)
        TransformContext ctx = new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), null);

        JsonNode result = expr.evaluate(input, ctx);

        // $session is NullNode → $session.sub evaluates to absent/null in JSLT
        assertThat(result.has("subject")).isFalse();
        assertThat(result.get("subject_present").asBoolean()).isFalse();
    }

    @Test
    void sessionContext_availableInRequestTransforms() throws Exception {
        // Session context should be available regardless of direction
        CompiledExpression expr = engine.compile(". + {\"tenant\": $session.tenant}");

        JsonNode input = MAPPER.readTree("{\"data\": 1}");
        JsonNode session = MAPPER.readTree("{\"tenant\": \"acme\"}");
        // Request context: status is null
        TransformContext ctx = new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), session);

        JsonNode result = expr.evaluate(input, ctx);

        assertThat(result.get("tenant").asText()).isEqualTo("acme");
    }

    @Test
    void sessionContext_availableInResponseTransforms() throws Exception {
        CompiledExpression expr = engine.compile(". + {\"user\": $session.sub}");

        JsonNode input = MAPPER.readTree("{\"status\": \"ok\"}");
        JsonNode session = MAPPER.readTree("{\"sub\": \"admin\"}");
        // Response context: status is 200
        TransformContext ctx = new TransformContext(Map.of(), Map.of(), 200, Map.of(), Map.of(), session);

        JsonNode result = expr.evaluate(input, ctx);

        assertThat(result.get("user").asText()).isEqualTo("admin");
    }

    @Test
    void sessionContext_coexistsWithOtherContextVariables() throws Exception {
        // Verify $session works alongside $headers, $status, etc.
        CompiledExpression expr =
                engine.compile("{\"subject\": $session.sub, \"host\": $headers.host, \"code\": $status}");

        JsonNode input = MAPPER.readTree("{}");
        JsonNode session = MAPPER.readTree("{\"sub\": \"bjensen\"}");
        TransformContext ctx =
                new TransformContext(Map.of("host", "api.example.com"), Map.of(), 200, Map.of(), Map.of(), session);

        JsonNode result = expr.evaluate(input, ctx);

        assertThat(result.get("subject").asText()).isEqualTo("bjensen");
        assertThat(result.get("host").asText()).isEqualTo("api.example.com");
        assertThat(result.get("code").asInt()).isEqualTo(200);
    }

    @Test
    void sessionContext_emptyObject() throws Exception {
        // Empty session context — $session.sub evaluates to absent in JSLT,
        // which means the field is omitted from the object constructor.
        CompiledExpression expr =
                engine.compile("if ($session.sub) {\"subject\": $session.sub} else {\"subject_present\": false}");

        JsonNode input = MAPPER.readTree("{}");
        JsonNode session = MAPPER.readTree("{}");
        TransformContext ctx = new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), session);

        JsonNode result = expr.evaluate(input, ctx);

        assertThat(result.has("subject")).isFalse();
        assertThat(result.get("subject_present").asBoolean()).isFalse();
    }

    @Test
    void sessionContext_nullNodeIsDistinctFromNull() throws Exception {
        // Explicit NullNode as session context → sessionContextAsJson() returns
        // NullNode
        TransformContext ctxNull =
                new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), NullNode.getInstance());

        JsonNode resultNull = ctxNull.sessionContextAsJson();
        assertThat(resultNull.isNull()).isTrue();

        // No session context (Java null) → sessionContextAsJson() also returns NullNode
        TransformContext ctxNoSession = new TransformContext(Map.of(), Map.of(), null, Map.of(), Map.of(), null);

        JsonNode resultNoSession = ctxNoSession.sessionContextAsJson();
        assertThat(resultNoSession.isNull()).isTrue();
    }
}
