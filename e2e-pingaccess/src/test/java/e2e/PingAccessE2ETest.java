package e2e;

import com.intuit.karate.junit5.Karate;

/**
 * JUnit 5 runner for PingAccess E2E Karate tests.
 *
 * <p>
 * Run via: {@code ./gradlew :e2e-pingaccess:test}
 *
 * <p>
 * Karate discovers all .feature files under this package and its sub-packages.
 * The execution order is controlled by the {@code @setup} tag in provision
 * features
 * and the {@code callonce} mechanism for shared setup state.
 */
class PingAccessE2ETest {

    @Karate.Test
    Karate testAll() {
        return Karate.run().relativeTo(getClass());
    }
}
