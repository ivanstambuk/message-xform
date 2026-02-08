package io.messagexform.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the core module has zero gateway-specific dependencies (NFR-001-02). This test
 * inspects the runtime classpath for known gateway SDK group IDs and fails if any are found.
 */
class CoreDependencyTest {

    /** Gateway SDK group IDs that MUST NOT appear on the core classpath. */
    private static final List<String> FORBIDDEN_GROUPS = List.of(
            "org.forgerock", // PingAM / PingGateway
            "com.pingidentity", // PingAccess
            "org.kong", // Kong
            "io.gravitee", // Gravitee
            "org.wso2", // WSO2
            "org.apache.apisix", // APISIX
            "io.openresty" // OpenResty
            );

    @Test
    void coreClasspathContainsNoGatewayDependencies() {
        String classpath = System.getProperty("java.class.path");
        assertThat(classpath).as("java.class.path should be set").isNotNull();

        for (String forbiddenGroup : FORBIDDEN_GROUPS) {
            String pathFragment = forbiddenGroup.replace('.', '/');
            assertThat(classpath)
                    .as("Core classpath must not contain gateway SDK: %s", forbiddenGroup)
                    .doesNotContain(pathFragment);
        }
    }
}
