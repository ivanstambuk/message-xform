package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingidentity.pa.sdk.policy.Rule;
import com.pingidentity.pa.sdk.policy.RuleInterceptorCategory;
import com.pingidentity.pa.sdk.policy.RuleInterceptorSupportedDestination;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MessageTransformRule} lifecycle (T-002-12, T-002-13,
 * T-002-14, FR-002-02, FR-002-03, FR-002-05).
 *
 * <p>
 * I4a covers the skeleton, annotation contract, callback, configure boot, and
 * cleanup hooks. Full transform flow tests are in a later increment (I7).
 */
class RuleLifecycleTest {

    // ---- T-002-12: Annotation contract ----

    @Nested
    class AnnotationContract {

        @Test
        void classIsAnnotatedWithRule() {
            Rule rule = MessageTransformRule.class.getAnnotation(Rule.class);
            assertThat(rule).as("@Rule annotation").isNotNull();
        }

        @Test
        void categoryIsProcessing() {
            Rule rule = MessageTransformRule.class.getAnnotation(Rule.class);
            assertThat(rule.category()).isEqualTo(RuleInterceptorCategory.Processing);
        }

        @Test
        void destinationIsSite() {
            Rule rule = MessageTransformRule.class.getAnnotation(Rule.class);
            assertThat(rule.destination()).containsExactly(RuleInterceptorSupportedDestination.Site);
        }

        @Test
        void labelIsMessageTransform() {
            Rule rule = MessageTransformRule.class.getAnnotation(Rule.class);
            assertThat(rule.label()).isEqualTo("Message Transform");
        }

        @Test
        void typeIsMessageTransform() {
            Rule rule = MessageTransformRule.class.getAnnotation(Rule.class);
            assertThat(rule.type()).isEqualTo("MessageTransform");
        }

        @Test
        void expectedConfigurationIsMessageTransformConfig() {
            Rule rule = MessageTransformRule.class.getAnnotation(Rule.class);
            assertThat(rule.expectedConfiguration()).isEqualTo(MessageTransformConfig.class);
        }
    }

    // ---- T-002-12: Callback contract ----

    @Nested
    class CallbackContract {

        @Test
        void getErrorHandlingCallbackReturnsNonNull() {
            MessageTransformRule rule = new MessageTransformRule();

            // The callback requires TemplateRenderer which is injected by PA.
            // Without injection, getErrorHandlingCallback should still not throw NPE
            // — it returns a callback instance using a null-safe pattern.
            // However, in the real PA lifecycle, TemplateRenderer is always set.
            // We verify the method is overridden and callable without NPE.
            assertThat(rule.getErrorHandlingCallback()).isNotNull();
        }
    }

    // ---- T-002-13: configure() validation and boot ----

    @Nested
    class ConfigureBoot {

        @Test
        void configureWithInvalidSpecsDirThrowsValidationException() {
            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir("/nonexistent/path/to/specs");

            assertThatThrownBy(() -> rule.configure(config)).isInstanceOf(jakarta.validation.ValidationException.class);
        }

        @Test
        void configureWithEmptySpecsDirSucceeds(@TempDir Path tempDir) throws Exception {
            // Empty specs dir is valid — warn but don't fail (ops may add files later)
            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());

            rule.configure(config);

            assertThat(rule.engine()).isNotNull();
            assertThat(rule.adapter()).isNotNull();
            assertThat(rule.engine().specCount()).isZero();
        }

        @Test
        void configureLoadsSpecFromDir(@TempDir Path tempDir) throws Exception {
            // Write a minimal valid spec YAML
            Path specFile = tempDir.resolve("test-spec.yaml");
            Files.writeString(
                    specFile,
                    String.join(
                            "\n",
                            "id: test-spec",
                            "version: '1'",
                            "input:",
                            "  schema:",
                            "    type: object",
                            "output:",
                            "  schema:",
                            "    type: object",
                            "transform:",
                            "  lang: jslt",
                            "  expr: \".\""));

            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());

            rule.configure(config);

            assertThat(rule.engine().specCount()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void configureWithInvalidSpecThrowsValidationException(@TempDir Path tempDir) throws Exception {
            Path specFile = tempDir.resolve("bad-spec.yaml");
            Files.writeString(specFile, "not: valid: yaml: spec");

            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());

            assertThatThrownBy(() -> rule.configure(config))
                    .isInstanceOf(jakarta.validation.ValidationException.class)
                    .hasMessageContaining("Failed to load spec");
        }
    }
}
