package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link ErrorMode} and {@link SchemaValidation} enums (T-002-01,
 * FR-002-04).
 *
 * <p>
 * Verifies enum values, admin-UI labels (via {@code toString()}), and
 * {@code values()} completeness. The {@code toString()} label is used by
 * PingAccess's {@code ConfigurationBuilder.from()} for SELECT option
 * auto-discovery (SDK guide §7).
 */
class EnumTest {

    // ---- ErrorMode ----

    @Test
    void errorModeHasTwoValues() {
        assertThat(ErrorMode.values()).hasSize(2);
    }

    @Test
    void errorModePassThroughLabel() {
        assertThat(ErrorMode.PASS_THROUGH.toString()).isEqualTo("Pass Through");
    }

    @Test
    void errorModeDenyLabel() {
        assertThat(ErrorMode.DENY.toString()).isEqualTo("Deny");
    }

    @ParameterizedTest
    @EnumSource(ErrorMode.class)
    void errorModeValueOfRoundTrips(ErrorMode mode) {
        assertThat(ErrorMode.valueOf(mode.name())).isSameAs(mode);
    }

    @Test
    void errorModeValuesOrder() {
        assertThat(ErrorMode.values()).containsExactly(ErrorMode.PASS_THROUGH, ErrorMode.DENY);
    }

    // ---- SchemaValidation ----

    @Test
    void schemaValidationHasTwoValues() {
        assertThat(SchemaValidation.values()).hasSize(2);
    }

    @Test
    void schemaValidationStrictLabel() {
        assertThat(SchemaValidation.STRICT.toString()).isEqualTo("Strict");
    }

    @Test
    void schemaValidationLenientLabel() {
        assertThat(SchemaValidation.LENIENT.toString()).isEqualTo("Lenient");
    }

    @ParameterizedTest
    @EnumSource(SchemaValidation.class)
    void schemaValidationValueOfRoundTrips(SchemaValidation mode) {
        assertThat(SchemaValidation.valueOf(mode.name())).isSameAs(mode);
    }

    @Test
    void schemaValidationValuesOrder() {
        assertThat(SchemaValidation.values()).containsExactly(SchemaValidation.STRICT, SchemaValidation.LENIENT);
    }
}
