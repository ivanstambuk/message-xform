package io.messagexform.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.ProfileResolveException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.TransformProfile;
import io.messagexform.core.model.TransformSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProfileParser} (T-001-28, FR-001-05, DO-001-04/08).
 */
@DisplayName("ProfileParser")
class ProfileParserTest {

    @TempDir
    Path tempDir;

    private Map<String, TransformSpec> specRegistry;

    private static TransformSpec dummySpec(String id, String version) {
        return new TransformSpec(id, version, null, "jslt", null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        specRegistry = new HashMap<>();
    }

    @Nested
    @DisplayName("Valid profile parsing")
    class ValidProfile {

        @Test
        @DisplayName("loads profile YAML with correct metadata")
        void loadsProfileMetadata() throws IOException {
            specRegistry.put("simple-rename@1.0.0", dummySpec("simple-rename", "1.0.0"));
            specRegistry.put("conditional-response@1.0.0", dummySpec("conditional-response", "1.0.0"));

            Path profilePath = Path.of("src/test/resources/test-profiles/pingam-callback-prettify.yaml");
            ProfileParser parser = new ProfileParser(specRegistry);
            TransformProfile profile = parser.parse(profilePath);

            assertThat(profile.id()).isEqualTo("pingam-callback-prettify");
            assertThat(profile.version()).isEqualTo("1.0.0");
            assertThat(profile.description())
                    .isEqualTo("Transforms PingAM callback responses into clean JSON for frontend consumption");
        }

        @Test
        @DisplayName("resolves entries with correct spec references")
        void resolvesEntrySpecReferences() {
            specRegistry.put("simple-rename@1.0.0", dummySpec("simple-rename", "1.0.0"));
            specRegistry.put("conditional-response@1.0.0", dummySpec("conditional-response", "1.0.0"));

            Path profilePath = Path.of("src/test/resources/test-profiles/pingam-callback-prettify.yaml");
            ProfileParser parser = new ProfileParser(specRegistry);
            TransformProfile profile = parser.parse(profilePath);

            assertThat(profile.entries()).hasSize(3);

            // Entry 0: simple-rename@1.0.0, POST /json/alpha/authenticate
            assertThat(profile.entries().get(0).spec().id()).isEqualTo("simple-rename");
            assertThat(profile.entries().get(0).spec().version()).isEqualTo("1.0.0");
            assertThat(profile.entries().get(0).direction()).isEqualTo(Direction.RESPONSE);
            assertThat(profile.entries().get(0).pathPattern()).isEqualTo("/json/alpha/authenticate");
            assertThat(profile.entries().get(0).method()).isEqualTo("POST");
            assertThat(profile.entries().get(0).contentType()).isEqualTo("application/json");

            // Entry 1: simple-rename@1.0.0, POST /json/*/authenticate (glob)
            assertThat(profile.entries().get(1).pathPattern()).isEqualTo("/json/*/authenticate");
            assertThat(profile.entries().get(1).method()).isEqualTo("POST");
            assertThat(profile.entries().get(1).contentType()).isNull();

            // Entry 2: conditional-response@1.0.0
            assertThat(profile.entries().get(2).spec().id()).isEqualTo("conditional-response");
            assertThat(profile.entries().get(2).pathPattern()).isEqualTo("/json/bravo/authenticate");
        }

        @Test
        @DisplayName("resolves unversioned spec reference to latest version")
        void resolvesUnversionedToLatest() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));
            specRegistry.put("my-spec@2.0.0", dummySpec("my-spec", "2.0.0"));
            specRegistry.put("my-spec@1.5.0", dummySpec("my-spec", "1.5.0"));

            Path profileYaml = tempDir.resolve("unversioned.yaml");
            Files.writeString(profileYaml, """
          profile: test-unversioned
          version: "1.0.0"
          transforms:
            - spec: my-spec
              direction: response
              match:
                path: "/test"
                method: GET
          """);

            ProfileParser parser = new ProfileParser(specRegistry);
            TransformProfile profile = parser.parse(profileYaml);

            assertThat(profile.entries().get(0).spec().version())
                    .as("should resolve to latest version (2.0.0)")
                    .isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("normalizes method to uppercase")
        void normalizesMethodToUppercase() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("lowercase-method.yaml");
            Files.writeString(profileYaml, """
          profile: test-method-case
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: request
              match:
                path: "/test"
                method: post
          """);

            ProfileParser parser = new ProfileParser(specRegistry);
            TransformProfile profile = parser.parse(profileYaml);

            assertThat(profile.entries().get(0).method()).isEqualTo("POST");
            assertThat(profile.entries().get(0).direction()).isEqualTo(Direction.REQUEST);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("missing spec reference → ProfileResolveException")
        void missingSpecReference() throws IOException {
            // Registry is empty — no specs loaded
            Path profileYaml = tempDir.resolve("missing-spec.yaml");
            Files.writeString(profileYaml, """
          profile: test-missing
          version: "1.0.0"
          transforms:
            - spec: nonexistent@1.0.0
              direction: response
              match:
                path: "/test"
                method: GET
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("nonexistent@1.0.0")
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("unknown version → ProfileResolveException")
        void unknownVersion() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("unknown-version.yaml");
            Files.writeString(profileYaml, """
          profile: test-unknown-version
          version: "1.0.0"
          transforms:
            - spec: my-spec@3.0.0
              direction: response
              match:
                path: "/test"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("my-spec@3.0.0")
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("missing direction → ProfileResolveException")
        void missingDirection() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("no-direction.yaml");
            Files.writeString(profileYaml, """
          profile: test-no-direction
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              match:
                path: "/test"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("direction");
        }

        @Test
        @DisplayName("invalid direction value → ProfileResolveException")
        void invalidDirection() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("bad-direction.yaml");
            Files.writeString(profileYaml, """
          profile: test-bad-direction
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: sideways
              match:
                path: "/test"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("sideways")
                    .hasMessageContaining("must be 'request' or 'response'");
        }

        @Test
        @DisplayName("empty transforms array → ProfileResolveException")
        void emptyTransforms() throws IOException {
            Path profileYaml = tempDir.resolve("empty-transforms.yaml");
            Files.writeString(profileYaml, """
          profile: test-empty
          version: "1.0.0"
          transforms: []
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("non-empty 'transforms'");
        }

        @Test
        @DisplayName("missing profile id → ProfileResolveException")
        void missingProfileId() throws IOException {
            Path profileYaml = tempDir.resolve("no-id.yaml");
            Files.writeString(profileYaml, """
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/test"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("profile");
        }

        @Test
        @DisplayName("missing match.path → ProfileResolveException")
        void missingMatchPath() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("no-path.yaml");
            Files.writeString(profileYaml, """
          profile: test-no-path
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                method: GET
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("path");
        }
    }

    @Nested
    @DisplayName("Specificity scoring")
    class SpecificityScoring {

        @Test
        @DisplayName("exact path has highest specificity")
        void exactPathHighestSpecificity() {
            TransformSpec spec = dummySpec("test", "1.0.0");
            var entry = new io.messagexform.core.model.ProfileEntry(
                    spec, Direction.RESPONSE, "/json/alpha/authenticate", "POST", null);
            assertThat(entry.specificityScore()).isEqualTo(3);
        }

        @Test
        @DisplayName("glob wildcard reduces specificity")
        void globWildcardReducesSpecificity() {
            TransformSpec spec = dummySpec("test", "1.0.0");
            var entry = new io.messagexform.core.model.ProfileEntry(
                    spec, Direction.RESPONSE, "/json/*/authenticate", "POST", null);
            assertThat(entry.specificityScore()).isEqualTo(2);
        }

        @Test
        @DisplayName("single wildcard has lowest specificity")
        void singleWildcard() {
            TransformSpec spec = dummySpec("test", "1.0.0");
            var entry = new io.messagexform.core.model.ProfileEntry(spec, Direction.RESPONSE, "/json/*", "POST", null);
            assertThat(entry.specificityScore()).isEqualTo(1);
        }

        @Test
        @DisplayName("constraint count for tie-breaking")
        void constraintCount() {
            TransformSpec spec = dummySpec("test", "1.0.0");
            var withBoth = new io.messagexform.core.model.ProfileEntry(
                    spec, Direction.RESPONSE, "/test", "POST", "application/json");
            var withMethod =
                    new io.messagexform.core.model.ProfileEntry(spec, Direction.RESPONSE, "/test", "POST", null);
            var withNeither =
                    new io.messagexform.core.model.ProfileEntry(spec, Direction.RESPONSE, "/test", null, null);

            assertThat(withBoth.constraintCount()).isEqualTo(2);
            assertThat(withMethod.constraintCount()).isEqualTo(1);
            assertThat(withNeither.constraintCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Version comparison")
    class VersionComparison {

        @Test
        @DisplayName("compares semver versions correctly")
        void comparesSemver() {
            assertThat(ProfileParser.compareVersions("1.0.0", "1.0.0")).isEqualTo(0);
            assertThat(ProfileParser.compareVersions("2.0.0", "1.0.0")).isGreaterThan(0);
            assertThat(ProfileParser.compareVersions("1.0.0", "2.0.0")).isLessThan(0);
            assertThat(ProfileParser.compareVersions("1.1.0", "1.0.0")).isGreaterThan(0);
            assertThat(ProfileParser.compareVersions("1.0.1", "1.0.0")).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Unknown key detection (T-001-70)")
    class UnknownKeyDetection {

        @Test
        @DisplayName("unknown root-level key → ProfileResolveException")
        void unknownRootKey() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("unknown-root.yaml");
            Files.writeString(profileYaml, """
          profile: test-unknown-root
          version: "1.0.0"
          routing:
            fallback: passthrough
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/test"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("Unknown key")
                    .hasMessageContaining("routing")
                    .hasMessageContaining("profile root");
        }

        @Test
        @DisplayName("unknown entry-level key → ProfileResolveException")
        void unknownEntryKey() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("unknown-entry.yaml");
            Files.writeString(profileYaml, """
          profile: test-unknown-entry
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              priority: 10
              match:
                path: "/test"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("Unknown key")
                    .hasMessageContaining("priority")
                    .hasMessageContaining("entry");
        }

        @Test
        @DisplayName("unknown match-block key → ProfileResolveException")
        void unknownMatchKey() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("unknown-match.yaml");
            Files.writeString(profileYaml, """
          profile: test-unknown-match
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/test"
                statis: "4xx"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("Unknown key")
                    .hasMessageContaining("statis")
                    .hasMessageContaining("match");
        }

        @Test
        @DisplayName("multiple unknown keys listed in error message")
        void multipleUnknownKeys() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("multi-unknown.yaml");
            Files.writeString(profileYaml, """
          profile: test-multi-unknown
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/test"
                statis: "4xx"
                header: "application/json"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("Unknown keys")
                    .hasMessageContaining("statis")
                    .hasMessageContaining("header");
        }

        @Test
        @DisplayName("valid profile with all known keys passes without error")
        void validProfileAllKnownKeys() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("all-known.yaml");
            Files.writeString(profileYaml, """
          profile: test-all-known
          version: "1.0.0"
          description: "A valid profile with all known keys"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                method: GET
                content-type: "application/json"
                status: "2xx"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);
            TransformProfile profile = parser.parse(profileYaml);

            assertThat(profile.id()).isEqualTo("test-all-known");
            assertThat(profile.entries()).hasSize(1);
        }
    }

    // ── match.when Parsing (FR-001-16, ADR-0036, T-001-71) ──

    @Nested
    @DisplayName("match.when Parsing")
    class WhenParsing {

        private EngineRegistry engineRegistry;

        @BeforeEach
        void setUpEngineRegistry() {
            engineRegistry = new EngineRegistry();
            engineRegistry.register(new JsltExpressionEngine());
        }

        @Test
        @DisplayName("valid when block → compiles and sets whenPredicate")
        void validWhenBlock() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("when-valid.yaml");
            Files.writeString(profileYaml, """
          profile: test-when
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  lang: jslt
                  expr: '.type == "admin"'
          """);

            ProfileParser parser = new ProfileParser(specRegistry, engineRegistry);
            TransformProfile profile = parser.parse(profileYaml);

            assertThat(profile.entries()).hasSize(1);
            assertThat(profile.entries().get(0).whenPredicate()).isNotNull();
            assertThat(profile.hasWhenPredicates()).isTrue();
        }

        @Test
        @DisplayName("when block missing lang → ProfileResolveException")
        void whenMissingLang() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("when-no-lang.yaml");
            Files.writeString(profileYaml, """
          profile: test-when-no-lang
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  expr: '.type == "admin"'
          """);

            ProfileParser parser = new ProfileParser(specRegistry, engineRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("'match.when' requires 'lang'");
        }

        @Test
        @DisplayName("when block missing expr → ProfileResolveException")
        void whenMissingExpr() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("when-no-expr.yaml");
            Files.writeString(profileYaml, """
          profile: test-when-no-expr
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  lang: jslt
          """);

            ProfileParser parser = new ProfileParser(specRegistry, engineRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("'match.when' requires 'expr'");
        }

        @Test
        @DisplayName("when block with lang=jolt → ProfileResolveException")
        void whenJoltRejected() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("when-jolt.yaml");
            Files.writeString(profileYaml, """
          profile: test-when-jolt
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  lang: jolt
                  expr: '[{"operation":"shift"}]'
          """);

            ProfileParser parser = new ProfileParser(specRegistry, engineRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("does not support lang 'jolt'")
                    .hasMessageContaining("Use 'jslt' instead");
        }

        @Test
        @DisplayName("when block with unknown keys → ProfileResolveException")
        void whenUnknownKeys() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("when-unknown.yaml");
            Files.writeString(profileYaml, """
          profile: test-when-unknown
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  lang: jslt
                  expr: '.type == "admin"'
                  timeout: 1000
          """);

            ProfileParser parser = new ProfileParser(specRegistry, engineRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("Unknown key")
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("when block without EngineRegistry → ProfileResolveException")
        void whenWithoutEngineRegistry() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("when-no-registry.yaml");
            Files.writeString(profileYaml, """
          profile: test-when-no-registry
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  lang: jslt
                  expr: '.type == "admin"'
          """);

            // No engine registry → should fail
            ProfileParser parser = new ProfileParser(specRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("engine registry");
        }

        @Test
        @DisplayName("when block with unregistered engine → ProfileResolveException")
        void whenUnregisteredEngine() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            // Create engine registry WITHOUT jslt
            EngineRegistry emptyRegistry = new EngineRegistry();

            Path profileYaml = tempDir.resolve("when-unregistered.yaml");
            Files.writeString(profileYaml, """
          profile: test-when-unregistered
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
                when:
                  lang: jslt
                  expr: '.type == "admin"'
          """);

            ProfileParser parser = new ProfileParser(specRegistry, emptyRegistry);

            assertThatThrownBy(() -> parser.parse(profileYaml))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("engine 'jslt' not registered");
        }

        @Test
        @DisplayName("profile without when → hasWhenPredicates() is false")
        void noWhenPredicatesReturnsFalse() throws IOException {
            specRegistry.put("my-spec@1.0.0", dummySpec("my-spec", "1.0.0"));

            Path profileYaml = tempDir.resolve("no-when.yaml");
            Files.writeString(profileYaml, """
          profile: test-no-when
          version: "1.0.0"
          transforms:
            - spec: my-spec@1.0.0
              direction: response
              match:
                path: "/api/**"
          """);

            ProfileParser parser = new ProfileParser(specRegistry);
            TransformProfile profile = parser.parse(profileYaml);

            assertThat(profile.entries().get(0).whenPredicate()).isNull();
            assertThat(profile.hasWhenPredicates()).isFalse();
        }
    }
}
