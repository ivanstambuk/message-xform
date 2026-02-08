package io.messagexform.core.model;

import io.messagexform.core.spi.CompiledExpression;

/**
 * Parsed status code transformation block from a transform spec (FR-001-11,
 * ADR-0003).
 * Immutable, thread-safe — created at load time by {@code SpecParser}.
 *
 * <p>
 * The {@code set} field is the target HTTP status code (100–599).
 * The optional {@code when} field is a compiled JSLT predicate evaluated
 * against the
 * <strong>transformed</strong> body. If {@code when} is null, the status is set
 * unconditionally.
 *
 * @param set  target HTTP status code (100–599)
 * @param when optional compiled JSLT predicate — status is only changed when
 *             this evaluates to true
 */
public record StatusSpec(
        int set,
        CompiledExpression when) {
}
