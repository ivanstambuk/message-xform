package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MessageTransformConfig} (T-002-02, FR-002-04, S-002-17,
 * S-002-18).
 *
 * <p>
 * Uses Jakarta Bean Validation (Hibernate Validator) to assert constraint
 * behaviour — the same validator PingAccess uses at runtime (FR-002-05,
 * step 5).
 */
class MessageTransformConfigTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // ---- Default values ----

    @Nested
    class Defaults {

        @Test
        void errorModeDefaultsToPassThrough() {
            var config = new MessageTransformConfig();
            assertThat(config.getErrorMode()).isEqualTo(ErrorMode.PASS_THROUGH);
        }

        @Test
        void reloadIntervalSecDefaultsToZero() {
            var config = new MessageTransformConfig();
            assertThat(config.getReloadIntervalSec()).isEqualTo(0);
        }

        @Test
        void schemaValidationDefaultsToLenient() {
            var config = new MessageTransformConfig();
            assertThat(config.getSchemaValidation()).isEqualTo(SchemaValidation.LENIENT);
        }

        @Test
        void enableJmxMetricsDefaultsToFalse() {
            var config = new MessageTransformConfig();
            assertThat(config.getEnableJmxMetrics()).isFalse();
        }

        @Test
        void specsDirDefaultsToSlashSpecs() {
            var config = new MessageTransformConfig();
            assertThat(config.getSpecsDir()).isEqualTo("/specs");
        }

        @Test
        void profilesDirDefaultsToSlashProfiles() {
            var config = new MessageTransformConfig();
            assertThat(config.getProfilesDir()).isEqualTo("/profiles");
        }

        @Test
        void activeProfileDefaultsToEmpty() {
            var config = new MessageTransformConfig();
            assertThat(config.getActiveProfile()).isEmpty();
        }
    }

    // ---- Required field validation ----

    @Nested
    class RequiredFields {

        @Test
        void validConfigHasNoViolations() {
            var config = validConfig();
            Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
            assertThat(violations).isEmpty();
        }

        @Test
        void specsDirNullIsViolation() {
            var config = validConfig();
            config.setSpecsDir(null);
            Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("specsDir"));
        }

        @Test
        void errorModeNullIsViolation() {
            var config = validConfig();
            config.setErrorMode(null);
            Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("errorMode"));
        }
    }

    // ---- Range validation ----

    @Nested
    class RangeValidation {

        @Test
        void reloadIntervalSecZeroIsValid() {
            var config = validConfig();
            config.setReloadIntervalSec(0);
            assertThat(validator.validate(config)).isEmpty();
        }

        @Test
        void reloadIntervalSecMaxIsValid() {
            var config = validConfig();
            config.setReloadIntervalSec(86400);
            assertThat(validator.validate(config)).isEmpty();
        }

        @Test
        void reloadIntervalSecNegativeIsViolation() {
            var config = validConfig();
            config.setReloadIntervalSec(-1);
            Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("reloadIntervalSec"));
        }

        @Test
        void reloadIntervalSecAboveMaxIsViolation() {
            var config = validConfig();
            config.setReloadIntervalSec(86401);
            Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("reloadIntervalSec"));
        }
    }

    // ---- Setter round-trips ----

    @Nested
    class SetterRoundTrips {

        @Test
        void setSpecsDir() {
            var config = new MessageTransformConfig();
            config.setSpecsDir("/opt/my-specs");
            assertThat(config.getSpecsDir()).isEqualTo("/opt/my-specs");
        }

        @Test
        void setProfilesDir() {
            var config = new MessageTransformConfig();
            config.setProfilesDir("/opt/profiles");
            assertThat(config.getProfilesDir()).isEqualTo("/opt/profiles");
        }

        @Test
        void setActiveProfile() {
            var config = new MessageTransformConfig();
            config.setActiveProfile("production");
            assertThat(config.getActiveProfile()).isEqualTo("production");
        }

        @Test
        void setErrorMode() {
            var config = new MessageTransformConfig();
            config.setErrorMode(ErrorMode.DENY);
            assertThat(config.getErrorMode()).isEqualTo(ErrorMode.DENY);
        }

        @Test
        void setReloadIntervalSec() {
            var config = new MessageTransformConfig();
            config.setReloadIntervalSec(300);
            assertThat(config.getReloadIntervalSec()).isEqualTo(300);
        }

        @Test
        void setSchemaValidation() {
            var config = new MessageTransformConfig();
            config.setSchemaValidation(SchemaValidation.STRICT);
            assertThat(config.getSchemaValidation()).isEqualTo(SchemaValidation.STRICT);
        }

        @Test
        void setEnableJmxMetrics() {
            var config = new MessageTransformConfig();
            config.setEnableJmxMetrics(true);
            assertThat(config.getEnableJmxMetrics()).isTrue();
        }
    }

    // ---- Name (inherited from SimplePluginConfiguration) ----

    @Nested
    class InheritedName {

        @Test
        void nameSetterAndGetter() {
            var config = new MessageTransformConfig();
            config.setName("My Transform Rule");
            assertThat(config.getName()).isEqualTo("My Transform Rule");
        }
    }

    // ---- Helper ----

    private static MessageTransformConfig validConfig() {
        var config = new MessageTransformConfig();
        // defaults are valid — specsDir="/specs", errorMode=PASS_THROUGH
        return config;
    }
}
