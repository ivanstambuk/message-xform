package io.messagexform.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.spi.CompiledExpression;
import java.util.Objects;

/**
 * Parsed and compiled transformation specification (DO-001-02). Immutable,
 * thread-safe — created at
 * load time by {@code SpecParser} and shared across concurrent requests.
 *
 * <p>
 * A spec defines either a single {@code transform} direction or bidirectional
 * {@code forward} +
 * {@code reverse} expressions. In the unidirectional case,
 * {@link #compiledExpr()} holds the
 * compiled expression and {@link #forward()}/{@link #reverse()} are null. In
 * the bidirectional
 * case, {@link #compiledExpr()} is null and both
 * {@link #forward()}/{@link #reverse()} are
 * populated.
 *
 * <p>
 * Fields populated in Phase 3: id, version, description, lang, inputSchema,
 * outputSchema,
 * compiledExpr, forward, reverse. Fields populated in Phase 6: headerSpec,
 * statusSpec.
 * Fields populated in later phases (null until then): sensitive, match, status,
 * mappers, url (FR-001-12/ADR-0027, Phase 6 / I11a).
 */
public record TransformSpec(
        String id,
        String version,
        String description,
        String lang,
        JsonNode inputSchema,
        JsonNode outputSchema,
        CompiledExpression compiledExpr,
        CompiledExpression forward,
        CompiledExpression reverse,
        HeaderSpec headerSpec,
        StatusSpec statusSpec) {

    /**
     * Canonical constructor — validates required fields.
     *
     * @throws NullPointerException if id, version, or lang is null
     */
    public TransformSpec {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(lang, "lang must not be null");
    }

    /**
     * Convenience constructor for specs without header operations (backward
     * compatibility).
     */
    public TransformSpec(
            String id,
            String version,
            String description,
            String lang,
            JsonNode inputSchema,
            JsonNode outputSchema,
            CompiledExpression compiledExpr,
            CompiledExpression forward,
            CompiledExpression reverse) {
        this(id, version, description, lang, inputSchema, outputSchema, compiledExpr, forward, reverse, null, null);
    }

    /**
     * Convenience constructor for specs with header ops but no status block.
     */
    public TransformSpec(
            String id,
            String version,
            String description,
            String lang,
            JsonNode inputSchema,
            JsonNode outputSchema,
            CompiledExpression compiledExpr,
            CompiledExpression forward,
            CompiledExpression reverse,
            HeaderSpec headerSpec) {
        this(id, version, description, lang, inputSchema, outputSchema, compiledExpr, forward, reverse, headerSpec,
                null);
    }

    /**
     * Returns {@code true} if this spec uses bidirectional forward/reverse
     * expressions.
     */
    public boolean isBidirectional() {
        return forward != null && reverse != null;
    }
}
