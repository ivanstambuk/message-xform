package io.messagexform.core.engine;

import io.messagexform.core.spi.ExpressionEngine;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for expression engines (FR-001-02, API-001-05). Manages engine registration and lookup
 * by engine id. Thread-safe — registration and lookup can happen concurrently.
 */
public final class EngineRegistry {

    private final Map<String, ExpressionEngine> engines = new ConcurrentHashMap<>();

    /**
     * Registers an expression engine. If an engine with the same id is already registered, it is
     * replaced (last-write-wins semantics).
     *
     * @param engine the expression engine to register
     * @throws NullPointerException if engine or engine.id() is null
     * @throws IllegalArgumentException if engine.id() is empty
     */
    public void register(ExpressionEngine engine) {
        if (engine == null) {
            throw new NullPointerException("engine must not be null");
        }
        String id = engine.id();
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("engine id must not be null or empty");
        }
        engines.put(id, engine);
    }

    /**
     * Looks up an engine by id.
     *
     * @param engineId the engine identifier (e.g. "jslt")
     * @return the engine, or empty if not registered
     */
    public Optional<ExpressionEngine> getEngine(String engineId) {
        return Optional.ofNullable(engines.get(engineId));
    }

    /**
     * Looks up an engine by id, throwing if not found.
     *
     * @param engineId the engine identifier
     * @return the registered engine
     * @throws IllegalArgumentException if no engine is registered with the given id
     */
    public ExpressionEngine requireEngine(String engineId) {
        return getEngine(engineId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No expression engine registered for id: '" + engineId + "'"));
    }

    /** Returns the number of registered engines. */
    public int size() {
        return engines.size();
    }

    /** Returns {@code true} if an engine with the given id is registered. */
    public boolean hasEngine(String engineId) {
        return engines.containsKey(engineId);
    }
}
