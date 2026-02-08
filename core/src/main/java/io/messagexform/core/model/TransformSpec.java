package io.messagexform.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.spi.CompiledExpression;
import java.util.List;
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
 * compiledExpr, forward, reverse. Fields populated in Phase 6 I10/I11:
 * headerSpec, statusSpec.
 * Fields populated in Phase 6 I11a: urlSpec (FR-001-12/ADR-0027).
 * Fields populated in Phase 6 I12: applySteps (FR-001-08/ADR-0014).
 * Fields populated in Phase 7 I13: sensitivePaths (NFR-001-06/ADR-0019).
 * Fields populated in later phases (null until then): match.
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
        StatusSpec statusSpec,
        UrlSpec urlSpec,
        List<ApplyStep> applySteps,
        List<String> sensitivePaths) {

    /**
     * Canonical constructor — validates required fields.
     *
     * @throws NullPointerException if id, version, or lang is null
     */
    public TransformSpec {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(lang, "lang must not be null");
        // applySteps may be null (no apply directive) or an immutable list
        // sensitivePaths may be null (no sensitive fields) or an immutable list
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
        this(
                id,
                version,
                description,
                lang,
                inputSchema,
                outputSchema,
                compiledExpr,
                forward,
                reverse,
                null,
                null,
                null,
                null,
                null);
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
        this(
                id,
                version,
                description,
                lang,
                inputSchema,
                outputSchema,
                compiledExpr,
                forward,
                reverse,
                headerSpec,
                null,
                null,
                null,
                null);
    }

    /**
     * Convenience constructor for specs with header + status but no URL block.
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
            HeaderSpec headerSpec,
            StatusSpec statusSpec) {
        this(
                id,
                version,
                description,
                lang,
                inputSchema,
                outputSchema,
                compiledExpr,
                forward,
                reverse,
                headerSpec,
                statusSpec,
                null,
                null,
                null);
    }

    /**
     * Convenience constructor for specs with header + status + URL but no apply
     * pipeline or sensitive paths.
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
            HeaderSpec headerSpec,
            StatusSpec statusSpec,
            UrlSpec urlSpec) {
        this(
                id,
                version,
                description,
                lang,
                inputSchema,
                outputSchema,
                compiledExpr,
                forward,
                reverse,
                headerSpec,
                statusSpec,
                urlSpec,
                null,
                null);
    }

    /**
     * Convenience constructor for specs with all features except sensitive paths.
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
            HeaderSpec headerSpec,
            StatusSpec statusSpec,
            UrlSpec urlSpec,
            List<ApplyStep> applySteps) {
        this(
                id,
                version,
                description,
                lang,
                inputSchema,
                outputSchema,
                compiledExpr,
                forward,
                reverse,
                headerSpec,
                statusSpec,
                urlSpec,
                applySteps,
                null);
    }

    /**
     * Returns {@code true} if this spec uses bidirectional forward/reverse
     * expressions.
     */
    public boolean isBidirectional() {
        return forward != null && reverse != null;
    }

    /**
     * Returns {@code true} if this spec defines an {@code apply} pipeline
     * (FR-001-08, ADR-0014).
     */
    public boolean hasApplyPipeline() {
        return applySteps != null && !applySteps.isEmpty();
    }

    /**
     * Returns {@code true} if this spec declares sensitive field paths
     * (NFR-001-06, ADR-0019).
     */
    public boolean hasSensitivePaths() {
        return sensitivePaths != null && !sensitivePaths.isEmpty();
    }
}
