package io.messagexform.core.engine;

import io.messagexform.core.model.TransformProfile;
import io.messagexform.core.model.TransformSpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of all loaded specs and the active profile (T-001-45,
 * NFR-001-05).
 *
 * <p>
 * This is the unit of atomic swap in {@link TransformEngine#reload()}. The
 * engine holds a {@code TransformRegistry} reference via
 * {@link java.util.concurrent.atomic.AtomicReference}; {@code reload()} builds
 * a new registry and atomically swaps it in. In-flight requests that captured
 * the old reference continue using it; new requests pick up the new one.
 *
 * <p>
 * Thread-safe: all fields are final and collections are unmodifiable.
 */
public final class TransformRegistry {

    private final Map<String, TransformSpec> specs;
    private final TransformProfile activeProfile;

    /**
     * Creates a new registry with the given specs and optional profile.
     * The specs map is defensively copied — the caller may mutate the original
     * after construction without affecting this registry.
     *
     * @param specs         map of spec keys (by id and by id@version) to specs
     * @param activeProfile the currently active profile, or null if none
     */
    public TransformRegistry(Map<String, TransformSpec> specs, TransformProfile activeProfile) {
        this.specs = Collections.unmodifiableMap(new HashMap<>(specs));
        this.activeProfile = activeProfile;
    }

    /**
     * Creates an empty registry with no specs and no profile.
     *
     * @return an empty, immutable registry
     */
    public static TransformRegistry empty() {
        return new TransformRegistry(Map.of(), null);
    }

    /**
     * Returns a new {@link Builder} for constructing a registry incrementally.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Looks up a spec by its id or by its {@code id@version} key.
     *
     * @param key the spec id or id@version string
     * @return the spec, or null if not found
     */
    public TransformSpec getSpec(String key) {
        return specs.get(key);
    }

    /**
     * Returns an unmodifiable view of all loaded specs (keyed by id and by
     * id@version).
     *
     * @return unmodifiable map of all specs
     */
    public Map<String, TransformSpec> allSpecs() {
        return specs;
    }

    /**
     * Returns the number of entries in the spec map (includes both by-id and
     * by-id@version keys).
     *
     * @return spec map entry count
     */
    public int specCount() {
        return specs.size();
    }

    /**
     * Returns the currently active profile, or null if no profile is loaded.
     *
     * @return the active profile, or null
     */
    public TransformProfile activeProfile() {
        return activeProfile;
    }

    /**
     * Builder for constructing a {@link TransformRegistry} incrementally.
     * Each {@link #addSpec(TransformSpec)} call registers the spec under both
     * its id and its {@code id@version} key (matching the registration pattern
     * used by {@link TransformEngine#loadSpec}).
     */
    public static final class Builder {

        private final Map<String, TransformSpec> specs = new HashMap<>();
        private TransformProfile activeProfile;

        Builder() {}

        /**
         * Registers a spec under both its id and its {@code id@version} key.
         *
         * @param spec the spec to register
         * @return this builder (fluent)
         */
        public Builder addSpec(TransformSpec spec) {
            specs.put(spec.id(), spec);
            specs.put(spec.id() + "@" + spec.version(), spec);
            return this;
        }

        /**
         * Sets the active profile.
         *
         * @param profile the profile to activate, or null for no profile
         * @return this builder (fluent)
         */
        public Builder activeProfile(TransformProfile profile) {
            this.activeProfile = profile;
            return this;
        }

        /**
         * Builds an immutable {@link TransformRegistry} from the accumulated
         * state.
         *
         * @return the new registry
         */
        public TransformRegistry build() {
            return new TransformRegistry(specs, activeProfile);
        }
    }
}
