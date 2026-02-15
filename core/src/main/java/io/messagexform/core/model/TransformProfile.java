package io.messagexform.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Parsed transform profile (DO-001-04). A profile is a self-contained YAML file
 * that composes transform specs with match criteria and direction (FR-001-05).
 *
 * <p>
 * Immutable, thread-safe — created at profile load time by
 * {@code ProfileParser}.
 *
 * @param id          profile identifier (e.g. "pingam-callback-prettify")
 * @param description human-readable description
 * @param version     profile version (semver string)
 * @param entries     ordered list of transform entries (declaration order
 *                    matters for chaining)
 */
public record TransformProfile(String id, String description, String version, List<ProfileEntry> entries) {

    /** Canonical constructor — validates required fields. */
    public TransformProfile {
        Objects.requireNonNull(id, "profile id must not be null");
        Objects.requireNonNull(version, "profile version must not be null");
        entries = entries != null ? Collections.unmodifiableList(entries) : List.of();
    }

    /**
     * Returns {@code true} if any entry in this profile has a {@code when}
     * predicate (FR-001-16, ADR-0036). Used by {@code TransformEngine} to
     * decide whether to pre-parse the message body before profile matching.
     *
     * <p>
     * This is a computed method (NOT a record component) because Java records
     * cannot have non-component fields. The iteration is O(n) over a small
     * list (typically 2–10 entries) and is called once per request.
     *
     * @return true if any entry has a non-null whenPredicate
     */
    public boolean hasWhenPredicates() {
        return entries.stream().anyMatch(e -> e.whenPredicate() != null);
    }
}
