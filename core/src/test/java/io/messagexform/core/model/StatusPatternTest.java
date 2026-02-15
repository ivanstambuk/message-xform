package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StatusPattern} (FR-001-15, ADR-0036).
 * Covers all pattern types: Exact, Class, Range, Not, AnyOf.
 */
@DisplayName("StatusPattern")
class StatusPatternTest {

    @Nested
    @DisplayName("Exact")
    class ExactTests {

        @Test
        @DisplayName("matches the exact code")
        void matchesExactCode() {
            var pattern = new StatusPattern.Exact(404);
            assertThat(pattern.matches(404)).isTrue();
        }

        @Test
        @DisplayName("does not match a different code")
        void noMatchDifferentCode() {
            var pattern = new StatusPattern.Exact(404);
            assertThat(pattern.matches(200)).isFalse();
            assertThat(pattern.matches(403)).isFalse();
            assertThat(pattern.matches(500)).isFalse();
        }

        @Test
        @DisplayName("specificity weight is 2")
        void specificityWeight() {
            assertThat(new StatusPattern.Exact(200).specificityWeight()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects codes outside 100-599")
        void rejectsInvalidCode() {
            assertThatThrownBy(() -> new StatusPattern.Exact(99)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatusPattern.Exact(600)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatusPattern.Exact(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatusPattern.Exact(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("boundary codes: 100 and 599 are valid")
        void boundaryCodes() {
            assertThat(new StatusPattern.Exact(100).matches(100)).isTrue();
            assertThat(new StatusPattern.Exact(599).matches(599)).isTrue();
        }
    }

    @Nested
    @DisplayName("Class")
    class ClassTests {

        @Test
        @DisplayName("2xx matches any 200-299 code")
        void matches2xx() {
            var pattern = new StatusPattern.Class(2);
            assertThat(pattern.matches(200)).isTrue();
            assertThat(pattern.matches(201)).isTrue();
            assertThat(pattern.matches(204)).isTrue();
            assertThat(pattern.matches(299)).isTrue();
        }

        @Test
        @DisplayName("4xx matches any 400-499 code")
        void matches4xx() {
            var pattern = new StatusPattern.Class(4);
            assertThat(pattern.matches(400)).isTrue();
            assertThat(pattern.matches(404)).isTrue();
            assertThat(pattern.matches(499)).isTrue();
        }

        @Test
        @DisplayName("4xx does not match 200 or 500")
        void noMatchDifferentClass() {
            var pattern = new StatusPattern.Class(4);
            assertThat(pattern.matches(200)).isFalse();
            assertThat(pattern.matches(500)).isFalse();
            assertThat(pattern.matches(302)).isFalse();
        }

        @Test
        @DisplayName("specificity weight is 1")
        void specificityWeight() {
            assertThat(new StatusPattern.Class(2).specificityWeight()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects invalid class digits")
        void rejectsInvalidClassDigit() {
            assertThatThrownBy(() -> new StatusPattern.Class(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatusPattern.Class(6)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Range")
    class RangeTests {

        @Test
        @DisplayName("matches codes within range (inclusive)")
        void matchesWithinRange() {
            var pattern = new StatusPattern.Range(400, 499);
            assertThat(pattern.matches(400)).isTrue();
            assertThat(pattern.matches(404)).isTrue();
            assertThat(pattern.matches(499)).isTrue();
        }

        @Test
        @DisplayName("does not match codes outside range")
        void noMatchOutsideRange() {
            var pattern = new StatusPattern.Range(400, 499);
            assertThat(pattern.matches(399)).isFalse();
            assertThat(pattern.matches(500)).isFalse();
            assertThat(pattern.matches(200)).isFalse();
        }

        @Test
        @DisplayName("specificity weight is 2")
        void specificityWeight() {
            assertThat(new StatusPattern.Range(200, 204).specificityWeight()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects low > high")
        void rejectsInvertedRange() {
            assertThatThrownBy(() -> new StatusPattern.Range(499, 400)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("single-code range works (low == high)")
        void singleCodeRange() {
            var pattern = new StatusPattern.Range(404, 404);
            assertThat(pattern.matches(404)).isTrue();
            assertThat(pattern.matches(403)).isFalse();
        }

        @Test
        @DisplayName("rejects out-of-range bounds")
        void rejectsOutOfRangeBounds() {
            assertThatThrownBy(() -> new StatusPattern.Range(99, 200)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatusPattern.Range(200, 600)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Not")
    class NotTests {

        @Test
        @DisplayName("negates an Exact pattern")
        void negatesExact() {
            var pattern = new StatusPattern.Not(new StatusPattern.Exact(404));
            assertThat(pattern.matches(404)).isFalse();
            assertThat(pattern.matches(200)).isTrue();
            assertThat(pattern.matches(500)).isTrue();
        }

        @Test
        @DisplayName("negates a Class pattern")
        void negatesClass() {
            var pattern = new StatusPattern.Not(new StatusPattern.Class(5));
            assertThat(pattern.matches(502)).isFalse();
            assertThat(pattern.matches(500)).isFalse();
            assertThat(pattern.matches(200)).isTrue();
            assertThat(pattern.matches(404)).isTrue();
        }

        @Test
        @DisplayName("specificity weight is 1")
        void specificityWeight() {
            assertThat(new StatusPattern.Not(new StatusPattern.Exact(404)).specificityWeight())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("rejects null inner")
        void rejectsNullInner() {
            assertThatThrownBy(() -> new StatusPattern.Not(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("AnyOf")
    class AnyOfTests {

        @Test
        @DisplayName("matches if any inner pattern matches")
        void matchesAnyInner() {
            var pattern = new StatusPattern.AnyOf(
                    List.of(new StatusPattern.Exact(200), new StatusPattern.Exact(201), new StatusPattern.Exact(204)));
            assertThat(pattern.matches(200)).isTrue();
            assertThat(pattern.matches(201)).isTrue();
            assertThat(pattern.matches(204)).isTrue();
            assertThat(pattern.matches(404)).isFalse();
        }

        @Test
        @DisplayName("supports mixed types: exact + class")
        void mixedTypes() {
            var pattern = new StatusPattern.AnyOf(List.of(new StatusPattern.Class(2), new StatusPattern.Exact(404)));
            assertThat(pattern.matches(200)).isTrue();
            assertThat(pattern.matches(204)).isTrue();
            assertThat(pattern.matches(404)).isTrue();
            assertThat(pattern.matches(500)).isFalse();
        }

        @Test
        @DisplayName("specificity weight is max of inner patterns")
        void specificityWeightMaxOfInner() {
            // Class = 1, Exact = 2 → max = 2
            var pattern = new StatusPattern.AnyOf(List.of(new StatusPattern.Class(2), new StatusPattern.Exact(404)));
            assertThat(pattern.specificityWeight()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects empty list")
        void rejectsEmptyList() {
            assertThatThrownBy(() -> new StatusPattern.AnyOf(List.of())).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null list")
        void rejectsNullList() {
            assertThatThrownBy(() -> new StatusPattern.AnyOf(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("list is defensively copied")
        void defensiveCopy() {
            var mutable = new java.util.ArrayList<StatusPattern>();
            mutable.add(new StatusPattern.Exact(200));
            var pattern = new StatusPattern.AnyOf(mutable);
            mutable.add(new StatusPattern.Exact(404)); // mutate original
            assertThat(pattern.patterns()).hasSize(1); // copy is unaffected
        }
    }
}
