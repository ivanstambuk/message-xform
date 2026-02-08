package io.messagexform.core.model;

import io.messagexform.core.spi.CompiledExpression;
import java.util.Objects;

/**
 * Represents a single step in a mapper {@code apply} pipeline (FR-001-08,
 * ADR-0014).
 *
 * <p>
 * Each step is either:
 * <ul>
 * <li>A reference to the main {@code expr} — {@link #isExpr()} returns
 * {@code true}</li>
 * <li>A reference to a named mapper — {@link #isExpr()} returns {@code false}
 * and
 * {@link #mapperRef()} / {@link #compiledMapper()} are populated</li>
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 */
public record ApplyStep(String mapperRef, CompiledExpression compiledMapper, boolean isExpr) {

    /**
     * Creates an {@code expr} step — references the main transform expression.
     */
    public static ApplyStep expr() {
        return new ApplyStep(null, null, true);
    }

    /**
     * Creates a {@code mapperRef} step — references a named mapper.
     *
     * @param mapperRef      the mapper identifier
     * @param compiledMapper the compiled mapper expression
     * @throws NullPointerException if either argument is null
     */
    public static ApplyStep mapper(String mapperRef, CompiledExpression compiledMapper) {
        Objects.requireNonNull(mapperRef, "mapperRef must not be null");
        Objects.requireNonNull(compiledMapper, "compiledMapper must not be null");
        return new ApplyStep(mapperRef, compiledMapper, false);
    }
}
