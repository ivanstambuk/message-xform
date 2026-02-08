package io.messagexform.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Smoke test that verifies the build and test infrastructure works. Remove once real tests exist.
 */
class SmokeTest {

    @Test
    void buildWorks() {
        assertThat(true).as("Build and test infrastructure is operational").isTrue();
    }
}
