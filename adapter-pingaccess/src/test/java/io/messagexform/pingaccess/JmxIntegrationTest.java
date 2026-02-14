package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JMX integration tests for MBean register/unregister lifecycle and counter
 * wiring (T-002-28, FR-002-14, S-002-33, S-002-34).
 *
 * <p>
 * Tests verify the full lifecycle: configure with enableJmxMetrics=true
 * registers the MBean, shutdown unregisters it, and enableJmxMetrics=false
 * does not register.
 */
class JmxIntegrationTest {

    private static final MBeanServer MBS = ManagementFactory.getPlatformMBeanServer();

    private MessageTransformRule rule;
    private ObjectName objectName;

    @AfterEach
    void cleanup() {
        if (rule != null) {
            rule.shutdown();
        }
        // Belt-and-suspenders: unregister if still present
        try {
            if (objectName != null && MBS.isRegistered(objectName)) {
                MBS.unregisterMBean(objectName);
            }
        } catch (Exception ignored) {
            // cleanup is best-effort
        }
    }

    private ObjectName buildObjectName(String instanceName) throws Exception {
        return new ObjectName("io.messagexform:type=TransformMetrics,instance=" + instanceName);
    }

    @Nested
    class OptInLifecycle {

        @Test
        void enableJmxMetricsRegistersMBean(@TempDir Path tempDir) throws Exception {
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(true);
            config.setName("jmx-test-register");

            rule.configure(config);

            objectName = buildObjectName("jmx-test-register");
            assertThat(MBS.isRegistered(objectName)).isTrue();
        }

        @Test
        void shutdownUnregistersMBean(@TempDir Path tempDir) throws Exception {
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(true);
            config.setName("jmx-test-unregister");

            rule.configure(config);
            objectName = buildObjectName("jmx-test-unregister");
            assertThat(MBS.isRegistered(objectName)).isTrue();

            rule.shutdown();
            assertThat(MBS.isRegistered(objectName)).isFalse();
        }

        @Test
        void metricsAccessibleViaJmx(@TempDir Path tempDir) throws Exception {
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(true);
            config.setName("jmx-test-access");

            rule.configure(config);

            objectName = buildObjectName("jmx-test-access");

            // Read attributes through JMX
            long successCount = (Long) MBS.getAttribute(objectName, "TransformSuccessCount");
            long totalCount = (Long) MBS.getAttribute(objectName, "TransformTotalCount");

            assertThat(successCount).isZero();
            assertThat(totalCount).isZero();
        }

        @Test
        void activeSpecCountReflectedInMBean(@TempDir Path tempDir) throws Exception {
            // Write a spec so engine has at least 1
            Path specFile = tempDir.resolve("jmx-spec.yaml");
            Files.writeString(
                    specFile,
                    String.join(
                            "\n",
                            "id: jmx-spec",
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

            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(true);
            config.setName("jmx-test-spec-count");

            rule.configure(config);

            objectName = buildObjectName("jmx-test-spec-count");
            long specCount = (Long) MBS.getAttribute(objectName, "ActiveSpecCount");
            assertThat(specCount).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    class OptOutLifecycle {

        @Test
        void disabledJmxDoesNotRegisterMBean(@TempDir Path tempDir) throws Exception {
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(false);
            config.setName("jmx-test-disabled");

            rule.configure(config);

            objectName = buildObjectName("jmx-test-disabled");
            assertThat(MBS.isRegistered(objectName)).isFalse();
        }

        @Test
        void defaultConfigDoesNotRegisterMBean(@TempDir Path tempDir) throws Exception {
            // enableJmxMetrics defaults to false
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setName("jmx-test-default");

            rule.configure(config);

            objectName = buildObjectName("jmx-test-default");
            assertThat(MBS.isRegistered(objectName)).isFalse();
        }

        @Test
        void shutdownWithNoMBeanIsNoOp(@TempDir Path tempDir) throws Exception {
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(false);
            config.setName("jmx-test-noop-shutdown");

            rule.configure(config);
            rule.shutdown(); // should not throw
        }
    }

    @Nested
    class ResetViaJmx {

        @Test
        void resetMetricsInvocableViaJmx(@TempDir Path tempDir) throws Exception {
            rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setEnableJmxMetrics(true);
            config.setName("jmx-test-reset");

            rule.configure(config);

            objectName = buildObjectName("jmx-test-reset");

            // Invoke resetMetrics via JMX
            MBS.invoke(objectName, "resetMetrics", null, null);

            long total = (Long) MBS.getAttribute(objectName, "TransformTotalCount");
            assertThat(total).isZero();
        }
    }
}
