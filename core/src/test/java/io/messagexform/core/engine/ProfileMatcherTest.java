package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.messagexform.core.model.Direction;
import io.messagexform.core.model.ProfileEntry;
import io.messagexform.core.model.StatusPattern;
import io.messagexform.core.model.TransformProfile;
import io.messagexform.core.model.TransformSpec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProfileMatcher} (T-001-29, FR-001-05, ADR-0006).
 */
@DisplayName("ProfileMatcher")
class ProfileMatcherTest {

    private static TransformSpec dummySpec(String id) {
        return new TransformSpec(id, "1.0.0", null, "jslt", null, null, null, null, null);
    }

    private static ProfileEntry entry(String specId, String path, String method, String ct, Direction dir) {
        return new ProfileEntry(dummySpec(specId), dir, path, method, ct);
    }

    private static ProfileEntry entryWithStatus(
            String specId, String path, String method, String ct, Direction dir, StatusPattern status) {
        return new ProfileEntry(dummySpec(specId), dir, path, method, ct, status);
    }

    @Nested
    @DisplayName("Path matching")
    class PathMatching {

        @Test
        @DisplayName("exact path match → entry selected")
        void exactPathMatch() {
            var profile = new TransformProfile(
                    "test",
                    null,
                    "1.0.0",
                    List.of(entry("spec-a", "/json/alpha/authenticate", "POST", null, Direction.RESPONSE)));

            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/json/alpha/authenticate", "POST", null, Direction.RESPONSE);

            assertThat(match).isNotNull();
            assertThat(match.spec().id()).isEqualTo("spec-a");
        }

        @Test
        @DisplayName("glob wildcard path matches")
        void globWildcardPath() {
            var profile = new TransformProfile(
                    "test",
                    null,
                    "1.0.0",
                    List.of(entry("spec-a", "/json/*/authenticate", "POST", null, Direction.RESPONSE)));

            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/json/delta/authenticate", "POST", null, Direction.RESPONSE);

            assertThat(match).isNotNull();
            assertThat(match.spec().id()).isEqualTo("spec-a");
        }

        @Test
        @DisplayName("double wildcard matches multi-segment path")
        void doubleWildcard() {
            var profile = new TransformProfile(
                    "test", null, "1.0.0", List.of(entry("spec-a", "/api/**", null, null, Direction.RESPONSE)));

            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/api/v1/users/123", null, null, Direction.RESPONSE);

            assertThat(match).isNotNull();
        }

        @Test
        @DisplayName("path mismatch → no match")
        void pathMismatch() {
            var profile = new TransformProfile(
                    "test",
                    null,
                    "1.0.0",
                    List.of(entry("spec-a", "/json/alpha/authenticate", "POST", null, Direction.RESPONSE)));

            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/json/bravo/authenticate", "POST", null, Direction.RESPONSE);

            assertThat(match).isNull();
        }
    }

    @Nested
    @DisplayName("Method + content-type filtering")
    class MethodAndContentType {

        @Test
        @DisplayName("method mismatch → no match")
        void methodMismatch() {
            var profile = new TransformProfile(
                    "test", null, "1.0.0", List.of(entry("spec-a", "/test", "POST", null, Direction.RESPONSE)));

            ProfileEntry match = ProfileMatcher.findBestMatch(profile, "/test", "GET", null, Direction.RESPONSE);

            assertThat(match).isNull();
        }

        @Test
        @DisplayName("content-type mismatch → no match")
        void contentTypeMismatch() {
            var profile = new TransformProfile(
                    "test",
                    null,
                    "1.0.0",
                    List.of(entry("spec-a", "/test", null, "application/json", Direction.RESPONSE)));

            ProfileEntry match = ProfileMatcher.findBestMatch(profile, "/test", null, "text/xml", Direction.RESPONSE);

            assertThat(match).isNull();
        }

        @Test
        @DisplayName("null method in entry matches any method")
        void nullMethodMatchesAny() {
            var profile = new TransformProfile(
                    "test", null, "1.0.0", List.of(entry("spec-a", "/test", null, null, Direction.RESPONSE)));

            ProfileEntry match = ProfileMatcher.findBestMatch(profile, "/test", "DELETE", null, Direction.RESPONSE);

            assertThat(match).isNotNull();
        }

        @Test
        @DisplayName("direction mismatch → no match")
        void directionMismatch() {
            var profile = new TransformProfile(
                    "test", null, "1.0.0", List.of(entry("spec-a", "/test", null, null, Direction.RESPONSE)));

            ProfileEntry match = ProfileMatcher.findBestMatch(profile, "/test", null, null, Direction.REQUEST);

            assertThat(match).isNull();
        }
    }

    @Nested
    @DisplayName("Specificity resolution (most-specific-wins)")
    class SpecificityResolution {

        @Test
        @DisplayName("exact path beats glob wildcard (ADR-0006)")
        void exactBeatsGlob() {
            var exact = entry("spec-exact", "/json/alpha/authenticate", "POST", null, Direction.RESPONSE);
            var glob = entry("spec-glob", "/json/*/authenticate", "POST", null, Direction.RESPONSE);

            var profile = new TransformProfile("test", null, "1.0.0", List.of(glob, exact));

            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/json/alpha/authenticate", "POST", null, Direction.RESPONSE);

            assertThat(match).isNotNull();
            assertThat(match.spec().id())
                    .as("exact path (specificity 3) should beat glob (specificity 2)")
                    .isEqualTo("spec-exact");
        }

        @Test
        @DisplayName("more constraints win on specificity tie")
        void moreConstraintsWinOnTie() {
            var withCt = entry("with-ct", "/test", "POST", "application/json", Direction.RESPONSE);
            var noCt = entry("no-ct", "/test", "POST", null, Direction.RESPONSE);

            var profile = new TransformProfile("test", null, "1.0.0", List.of(noCt, withCt));

            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/test", "POST", "application/json", Direction.RESPONSE);

            assertThat(match).isNotNull();
            assertThat(match.spec().id())
                    .as("entry with method + content-type (2 constraints) beats method-only (1 constraint)")
                    .isEqualTo("with-ct");
        }

        @Test
        @DisplayName("findMatches returns all matching entries sorted by specificity")
        void findMatchesReturnsSorted() {
            var exact = entry("spec-exact", "/json/alpha/authenticate", "POST", null, Direction.RESPONSE);
            var glob = entry("spec-glob", "/json/*/authenticate", "POST", null, Direction.RESPONSE);

            var profile = new TransformProfile("test", null, "1.0.0", List.of(glob, exact));

            List<ProfileEntry> matches =
                    ProfileMatcher.findMatches(profile, "/json/alpha/authenticate", "POST", null, Direction.RESPONSE);

            assertThat(matches).hasSize(2);
            assertThat(matches.get(0).spec().id()).isEqualTo("spec-exact"); // higher specificity
            assertThat(matches.get(1).spec().id()).isEqualTo("spec-glob"); // lower specificity
        }
    }

    @Nested
    @DisplayName("No match → passthrough")
    class NoMatch {

        @Test
        @DisplayName("no matching entry → null (passthrough)")
        void noMatch() {
            var profile = new TransformProfile(
                    "test",
                    null,
                    "1.0.0",
                    List.of(entry("spec-a", "/json/alpha/authenticate", "POST", null, Direction.RESPONSE)));

            ProfileEntry match = ProfileMatcher.findBestMatch(
                    profile, "/completely/different/path", "GET", null, Direction.RESPONSE);

            assertThat(match).isNull();
        }

        @Test
        @DisplayName("empty profile → null")
        void emptyProfile() {
            var profile = new TransformProfile("test", null, "1.0.0", List.of());

            ProfileEntry match = ProfileMatcher.findBestMatch(profile, "/test", "GET", null, Direction.RESPONSE);

            assertThat(match).isNull();
        }
    }

    @Nested
    @DisplayName("Status-code matching (FR-001-15, ADR-0036)")
    class StatusMatching {

        @Test
        @DisplayName("S-001-87: 2xx class matches 200, 4xx doesn't")
        void statusClassRouting() {
            var success = entryWithStatus(
                    "success-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(2));
            var error = entryWithStatus(
                    "error-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(4));
            var profile = new TransformProfile("test", null, "1.0.0", List.of(success, error));

            // 200 → matches success spec
            List<ProfileEntry> matches200 =
                    ProfileMatcher.findMatches(profile, "/api/test", null, null, Direction.RESPONSE, 200);
            assertThat(matches200).hasSize(1);
            assertThat(matches200.get(0).spec().id()).isEqualTo("success-spec");

            // 404 → matches error spec
            List<ProfileEntry> matches404 =
                    ProfileMatcher.findMatches(profile, "/api/test", null, null, Direction.RESPONSE, 404);
            assertThat(matches404).hasSize(1);
            assertThat(matches404.get(0).spec().id()).isEqualTo("error-spec");
        }

        @Test
        @DisplayName("S-001-88: exact 404 matches, 200 doesn't")
        void statusExactMatch() {
            var notFound = entryWithStatus(
                    "not-found-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Exact(404));
            var profile = new TransformProfile("test", null, "1.0.0", List.of(notFound));

            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 404))
                    .isNotNull();
            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 200))
                    .isNull();
        }

        @Test
        @DisplayName("status + path combined matching")
        void statusPlusPath() {
            var wide =
                    entryWithStatus("wide-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(4));
            var narrow = entryWithStatus(
                    "narrow-spec", "/api/users/**", null, null, Direction.RESPONSE, new StatusPattern.Class(4));
            var profile = new TransformProfile("test", null, "1.0.0", List.of(wide, narrow));

            // /api/users/123 + 404 → narrow wins (higher specificity)
            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/api/users/123", null, null, Direction.RESPONSE, 404);
            assertThat(match).isNotNull();
            assertThat(match.spec().id()).isEqualTo("narrow-spec");
        }

        @Test
        @DisplayName("null statusCode with status pattern → non-matching (defensive)")
        void nullStatusCodeWithPattern() {
            var withStatus = entryWithStatus(
                    "error-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(4));
            var profile = new TransformProfile("test", null, "1.0.0", List.of(withStatus));

            // null status code → no match (defensive)
            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, null))
                    .isNull();
        }

        @Test
        @DisplayName("no status pattern → matches any status code")
        void noStatusPatternMatchesAny() {
            var noStatus = entry("any-spec", "/api/**", null, null, Direction.RESPONSE);
            var profile = new TransformProfile("test", null, "1.0.0", List.of(noStatus));

            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 200))
                    .isNotNull();
            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 404))
                    .isNotNull();
            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 500))
                    .isNotNull();
        }

        @Test
        @DisplayName("S-001-92: exact beats class for same code (specificity)")
        void exactBeatsClassSpecificity() {
            var classEntry = entryWithStatus(
                    "class-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(4));
            var exactEntry = entryWithStatus(
                    "exact-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Exact(404));
            var profile = new TransformProfile("test", null, "1.0.0", List.of(classEntry, exactEntry));

            // Both match 404, but exact (weight 2) > class (weight 1) in constraintCount
            ProfileEntry match =
                    ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 404);
            assertThat(match).isNotNull();
            assertThat(match.spec().id())
                    .as("exact status (weight 2) beats class (weight 1) in constraint count")
                    .isEqualTo("exact-spec");
        }

        @Test
        @DisplayName("unmatched status → passthrough (S-001-96 partial)")
        void unmatchedStatusPassthrough() {
            var success = entryWithStatus(
                    "success-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(2));
            var error = entryWithStatus(
                    "error-spec", "/api/**", null, null, Direction.RESPONSE, new StatusPattern.Class(4));
            var profile = new TransformProfile("test", null, "1.0.0", List.of(success, error));

            // 301 → neither 2xx nor 4xx → no match → passthrough
            assertThat(ProfileMatcher.findBestMatch(profile, "/api/test", null, null, Direction.RESPONSE, 301))
                    .isNull();
        }
    }
}
