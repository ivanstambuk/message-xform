package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor;
import com.pingidentity.pa.sdk.services.ServiceFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for SPI registration (T-002-16, FR-002-08).
 *
 * <p>
 * Verifies that the {@code META-INF/services} file correctly registers
 * {@link MessageTransformRule} as an {@link AsyncRuleInterceptor}
 * implementation, and that PingAccess's {@link ServiceFactory} can discover it.
 */
class SpiRegistrationTest {

    private static final String FQCN = "io.messagexform.pingaccess.MessageTransformRule";

    @Nested
    class ServiceFilePresence {

        @Test
        void serviceFileExistsOnClasspath() {
            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor");
            assertThat(is).as("SPI service file on classpath").isNotNull();
        }

        @Test
        void serviceFileContainsFqcn() throws Exception {
            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor");
            assertThat(is).isNotNull();

            List<String> lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                lines = reader.lines()
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .toList();
            }
            assertThat(lines).contains(FQCN);
        }
    }

    @Nested
    class ServiceFactoryDiscovery {

        @Test
        void serviceFactoryRecognizesImplName() {
            assertThat(ServiceFactory.isValidImplName(AsyncRuleInterceptor.class, FQCN))
                    .as("ServiceFactory.isValidImplName for MessageTransformRule")
                    .isTrue();
        }

        @Test
        void pingAccessAdapterIsNotRegisteredAsPlugin() {
            // PingAccessAdapter is an internal helper, NOT a plugin
            assertThat(ServiceFactory.isValidImplName(
                            AsyncRuleInterceptor.class, "io.messagexform.pingaccess.PingAccessAdapter"))
                    .as("PingAccessAdapter must NOT be registered as a plugin")
                    .isFalse();
        }
    }
}
