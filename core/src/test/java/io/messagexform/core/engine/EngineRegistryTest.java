package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spi.CompiledExpression;
import io.messagexform.core.spi.ExpressionEngine;
import org.junit.jupiter.api.Test;

/** Tests for {@link EngineRegistry} (FR-001-02, API-001-05). */
class EngineRegistryTest {

    /** Trivial mock engine for testing. */
    private static ExpressionEngine mockEngine(String id) {
        return new ExpressionEngine() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompiledExpression compile(String expression) {
                return (input, context) -> input;
            }
        };
    }

    @Test
    void registerAndRetrieveEngine() {
        var registry = new EngineRegistry();
        var jslt = new JsltExpressionEngine();
        registry.register(jslt);

        assertThat(registry.getEngine("jslt")).isPresent().hasValue(jslt);
        assertThat(registry.requireEngine("jslt")).isSameAs(jslt);
    }

    @Test
    void getEngineReturnsEmptyForUnknownId() {
        var registry = new EngineRegistry();

        assertThat(registry.getEngine("nonexistent")).isEmpty();
    }

    @Test
    void requireEngineThrowsForUnknownId() {
        var registry = new EngineRegistry();

        assertThatThrownBy(() -> registry.requireEngine("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void registerDuplicateIdReplacesEngine() {
        var registry = new EngineRegistry();
        var engine1 = mockEngine("custom");
        var engine2 = mockEngine("custom");

        registry.register(engine1);
        registry.register(engine2);

        assertThat(registry.requireEngine("custom")).isSameAs(engine2);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void registerNullEngineThrowsNpe() {
        var registry = new EngineRegistry();

        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerEngineWithNullIdThrowsIae() {
        var registry = new EngineRegistry();
        var badEngine = mockEngine(null);

        assertThatThrownBy(() -> registry.register(badEngine)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerEngineWithEmptyIdThrowsIae() {
        var registry = new EngineRegistry();
        var badEngine = mockEngine("");

        assertThatThrownBy(() -> registry.register(badEngine)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasEngineReturnsTrueWhenRegistered() {
        var registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());

        assertThat(registry.hasEngine("jslt")).isTrue();
        assertThat(registry.hasEngine("jolt")).isFalse();
    }

    @Test
    void sizeReflectsRegisteredEngineCount() {
        var registry = new EngineRegistry();
        assertThat(registry.size()).isEqualTo(0);

        registry.register(mockEngine("a"));
        registry.register(mockEngine("b"));
        assertThat(registry.size()).isEqualTo(2);
    }
}
