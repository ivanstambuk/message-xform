package io.messagexform.core.model;

import io.messagexform.core.spi.CompiledExpression;
import java.util.List;
import java.util.Map;

/**
 * Parsed URL rewrite block from a transform spec (FR-001-12, ADR-0027).
 * Immutable, thread-safe — created at load time by {@code SpecParser}.
 *
 * <p>
 * All URL expressions evaluate against the <strong>original</strong>
 * (pre-transform) body,
 * not the transformed body. This is a documented exception to the convention
 * used by
 * {@code headers.add.expr} and {@code status.when} (ADR-0027: "route the input,
 * enrich
 * the output").
 *
 * <p>
 * Processing order within the URL block:
 * <ol>
 * <li>{@code path.expr} → evaluated, produces new request path (string)</li>
 * <li>{@code query.remove} → strip matching query parameters (glob
 * patterns)</li>
 * <li>{@code query.add} (static) → set parameters with literal values</li>
 * <li>{@code query.add} (dynamic) → evaluate expr against original body</li>
 * <li>{@code method.when} predicate → if true (or absent), {@code method.set}
 * applied</li>
 * </ol>
 *
 * @param pathExpr        optional compiled JSLT expression for path rewrite
 *                        (must return string)
 * @param queryRemove     glob patterns for query parameters to remove (may be
 *                        empty)
 * @param queryStaticAdd  static query parameter values to add (may be empty)
 * @param queryDynamicAdd dynamic query parameter expressions to evaluate (may
 *                        be empty)
 * @param methodSet       optional target HTTP method (GET, POST, PUT, DELETE,
 *                        PATCH, HEAD, OPTIONS)
 * @param methodWhen      optional compiled JSLT predicate for conditional
 *                        method override
 */
public record UrlSpec(
        CompiledExpression pathExpr,
        List<String> queryRemove,
        Map<String, String> queryStaticAdd,
        Map<String, CompiledExpression> queryDynamicAdd,
        String methodSet,
        CompiledExpression methodWhen) {

    /** Canonical constructor with defensive defaults. */
    public UrlSpec {
        queryRemove = queryRemove != null ? List.copyOf(queryRemove) : List.of();
        queryStaticAdd = queryStaticAdd != null ? Map.copyOf(queryStaticAdd) : Map.of();
        queryDynamicAdd = queryDynamicAdd != null ? Map.copyOf(queryDynamicAdd) : Map.of();
    }

    /** Returns {@code true} if this URL spec has a path rewrite expression. */
    public boolean hasPathRewrite() {
        return pathExpr != null;
    }

    /** Returns {@code true} if this URL spec has a method override. */
    public boolean hasMethodOverride() {
        return methodSet != null;
    }

    /** Returns {@code true} if this URL spec has any query parameter operations. */
    public boolean hasQueryOperations() {
        return !queryRemove.isEmpty() || !queryStaticAdd.isEmpty() || !queryDynamicAdd.isEmpty();
    }
}
