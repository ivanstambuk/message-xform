package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.model.Direction;
import io.messagexform.core.model.ProfileEntry;
import io.messagexform.core.model.TransformProfile;
import io.messagexform.core.model.TransformSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TransformRegistry} — the immutable snapshot of loaded specs
 * and profiles (T-001-45, NFR-001-05).
 *
 * <p>
 * TransformRegistry holds the full set of compiled specs and an optional active
 * profile. It is the unit of atomic swap in {@code TransformEngine.reload()}.
 */
class TransformRegistryTest {

    // --- Fixtures ---

    private static TransformSpec stubSpec(String id, String version) {
        return new TransformSpec(id, version, null, "jslt", null, null, null, null, null);
    }

    private static TransformProfile stubProfile(String id, TransformSpec spec) {
        ProfileEntry entry = new ProfileEntry(spec, Direction.RESPONSE, "/test", "POST", null);
        return new TransformProfile(id, "test profile", "1.0.0", List.of(entry));
    }

    // --- Construction & basic accessors ---

    @Test
    void emptyRegistryHasNoSpecsAndNoProfile() {
        TransformRegistry registry = TransformRegistry.empty();

        assertThat(registry.specCount()).isZero();
        assertThat(registry.activeProfile()).isNull();
        assertThat(registry.allSpecs()).isEmpty();
    }

    @Test
    void registryHoldsSpecsByIdAndByIdAtVersion() {
        TransformSpec spec = stubSpec("my-spec", "1.0.0");
        Map<String, TransformSpec> specs = Map.of(
                "my-spec", spec,
                "my-spec@1.0.0", spec);

        TransformRegistry registry = new TransformRegistry(specs, null);

        assertThat(registry.specCount()).isEqualTo(2);
        assertThat(registry.getSpec("my-spec")).isSameAs(spec);
        assertThat(registry.getSpec("my-spec@1.0.0")).isSameAs(spec);
    }

    @Test
    void registryHoldsActiveProfile() {
        TransformSpec spec = stubSpec("spec-a", "2.0.0");
        TransformProfile profile = stubProfile("profile-1", spec);
        Map<String, TransformSpec> specs = Map.of("spec-a", spec);

        TransformRegistry registry = new TransformRegistry(specs, profile);

        assertThat(registry.activeProfile()).isSameAs(profile);
    }

    @Test
    void getSpecReturnsNullForUnknownId() {
        TransformRegistry registry = TransformRegistry.empty();

        assertThat(registry.getSpec("nonexistent")).isNull();
    }

    // --- Immutability ---

    @Test
    void allSpecsReturnsUnmodifiableView() {
        TransformSpec spec = stubSpec("immutable-spec", "1.0.0");
        Map<String, TransformSpec> specs = Map.of("immutable-spec", spec);
        TransformRegistry registry = new TransformRegistry(specs, null);

        Map<String, TransformSpec> returned = registry.allSpecs();
        assertThatThrownBy(() -> returned.put("hacked", spec)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void registryIsDefensivelyCopied() {
        TransformSpec spec = stubSpec("original", "1.0.0");
        java.util.HashMap<String, TransformSpec> mutableSpecs = new java.util.HashMap<>();
        mutableSpecs.put("original", spec);

        TransformRegistry registry = new TransformRegistry(mutableSpecs, null);

        // Mutate the original map after construction — should NOT affect the registry
        mutableSpecs.put("injected", stubSpec("injected", "1.0.0"));

        assertThat(registry.specCount()).isEqualTo(1);
        assertThat(registry.getSpec("injected")).isNull();
    }

    // --- Multiple specs ---

    @Test
    void registryHoldsMultipleSpecs() {
        TransformSpec specA = stubSpec("spec-a", "1.0.0");
        TransformSpec specB = stubSpec("spec-b", "2.0.0");
        TransformSpec specA2 = stubSpec("spec-a", "2.0.0");

        Map<String, TransformSpec> specs = Map.of(
                "spec-a", specA,
                "spec-a@1.0.0", specA,
                "spec-a@2.0.0", specA2,
                "spec-b", specB,
                "spec-b@2.0.0", specB);

        TransformRegistry registry = new TransformRegistry(specs, null);

        assertThat(registry.specCount()).isEqualTo(5);
        assertThat(registry.getSpec("spec-a")).isSameAs(specA);
        assertThat(registry.getSpec("spec-a@1.0.0")).isSameAs(specA);
        assertThat(registry.getSpec("spec-a@2.0.0")).isSameAs(specA2);
        assertThat(registry.getSpec("spec-b")).isSameAs(specB);
    }

    // --- Builder pattern ---

    @Test
    void builderCreatesRegistryFromSpecsAndProfile() {
        TransformSpec spec = stubSpec("builder-spec", "1.0.0");
        TransformProfile profile = stubProfile("builder-profile", spec);

        TransformRegistry registry =
                TransformRegistry.builder().addSpec(spec).activeProfile(profile).build();

        assertThat(registry.getSpec("builder-spec")).isSameAs(spec);
        assertThat(registry.getSpec("builder-spec@1.0.0")).isSameAs(spec);
        assertThat(registry.activeProfile()).isSameAs(profile);
    }

    @Test
    void builderAddsMultipleSpecs() {
        TransformSpec specA = stubSpec("alpha", "1.0.0");
        TransformSpec specB = stubSpec("beta", "3.0.0");

        TransformRegistry registry =
                TransformRegistry.builder().addSpec(specA).addSpec(specB).build();

        assertThat(registry.getSpec("alpha")).isSameAs(specA);
        assertThat(registry.getSpec("alpha@1.0.0")).isSameAs(specA);
        assertThat(registry.getSpec("beta")).isSameAs(specB);
        assertThat(registry.getSpec("beta@3.0.0")).isSameAs(specB);
    }

    @Test
    void builderWithNoProfileLeavesItNull() {
        TransformSpec spec = stubSpec("lonely", "1.0.0");

        TransformRegistry registry = TransformRegistry.builder().addSpec(spec).build();

        assertThat(registry.activeProfile()).isNull();
    }

    @Test
    void emptyBuilderCreatesEmptyRegistry() {
        TransformRegistry registry = TransformRegistry.builder().build();

        assertThat(registry.specCount()).isZero();
        assertThat(registry.activeProfile()).isNull();
    }
}
