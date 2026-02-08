package io.messagexform.core.model;

import io.messagexform.core.spi.CompiledExpression;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed header operations block from a transform spec (FR-001-10).
 * Immutable, thread-safe — created at load time by {@code SpecParser}.
 *
 * <p>
 * Processing order (per spec): remove → rename → add (static) → add (dynamic).
 *
 * @param staticAdd  header name → static string value to add
 * @param dynamicAdd header name → compiled expression evaluated against the
 *                   transformed body
 * @param remove     glob patterns for headers to remove
 * @param rename     old header name → new header name
 */
public record HeaderSpec(
        Map<String, String> staticAdd,
        Map<String, CompiledExpression> dynamicAdd,
        List<String> remove,
        Map<String, String> rename) {

    /** Canonical constructor with defensive copies. */
    public HeaderSpec {
        staticAdd = staticAdd != null ? Collections.unmodifiableMap(staticAdd) : Map.of();
        dynamicAdd = dynamicAdd != null ? Collections.unmodifiableMap(dynamicAdd) : Map.of();
        remove = remove != null ? Collections.unmodifiableList(remove) : List.of();
        rename = rename != null ? Collections.unmodifiableMap(rename) : Map.of();
    }

    /** Creates an empty header spec (no operations). */
    public static HeaderSpec empty() {
        return new HeaderSpec(null, null, null, null);
    }

    /** Returns {@code true} if this spec has no operations. */
    public boolean isEmpty() {
        return staticAdd.isEmpty() && dynamicAdd.isEmpty() && remove.isEmpty() && rename.isEmpty();
    }
}
