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
}
