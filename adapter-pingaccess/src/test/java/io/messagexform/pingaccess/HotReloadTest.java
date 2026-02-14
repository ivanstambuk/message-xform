package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for spec hot-reload lifecycle (T-002-25, T-002-26, FR-002-04,
 * S-002-29, S-002-30, S-002-31).
 *
 * <p>
 * T-002-25 covers scheduler lifecycle: daemon thread, @PreDestroy shutdown.
 * T-002-26 covers reload task robustness: success, failure recovery, profile
 * path resolution.
 *
 * <p>
 * All tests use real engine instances but configure short reload intervals
 * and verify outcomes through the engine's specCount() accessor.
 */
class HotReloadTest {

    // ── T-002-25: Scheduler lifecycle ──

    @Nested
    class ThreadLifecycle {

        @Test
        void reloadIntervalZeroDoesNotStartScheduler(@TempDir Path tempDir) throws Exception {
            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setReloadIntervalSec(0);

            rule.configure(config);

            // No background thread running
            assertThat(rule.reloadExecutor()).isNull();
        }

        @Test
        void reloadIntervalPositiveStartsDaemonScheduler(@TempDir Path tempDir) throws Exception {
            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setReloadIntervalSec(5);

            rule.configure(config);

            try {
                assertThat(rule.reloadExecutor()).isNotNull();
                assertThat(rule.reloadExecutor().isShutdown()).isFalse();
            } finally {
                rule.shutdown();
            }
        }

        @Test
        void shutdownTerminatesScheduler(@TempDir Path tempDir) throws Exception {
            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setReloadIntervalSec(5);

            rule.configure(config);
            assertThat(rule.reloadExecutor()).isNotNull();

            rule.shutdown();

            assertThat(rule.reloadExecutor().isShutdown()).isTrue();
        }

        @Test
        void shutdownWithNoSchedulerIsNoOp() {
            MessageTransformRule rule = new MessageTransformRule();
            // No configure() called — no scheduler
            rule.shutdown(); // should not throw
        }

        @Test
        void shutdownIdempotent(@TempDir Path tempDir) throws Exception {
            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setReloadIntervalSec(5);

            rule.configure(config);
            rule.shutdown();
            rule.shutdown(); // second call should not throw
        }
    }

    // ── T-002-26: Reload task robustness ──

    @Nested
    class ReloadBehavior {

        /**
         * Writes a minimal valid spec YAML file.
         */
        private Path writeSpec(Path dir, String id) throws Exception {
            Path file = dir.resolve(id + ".yaml");
            Files.writeString(
                    file,
                    String.join(
                            "\n",
                            "id: " + id,
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
            return file;
        }

        @Test
        void reloadPicksUpNewSpec(@TempDir Path tempDir) throws Exception {
            // Start with one spec
            writeSpec(tempDir, "spec-a");

            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setReloadIntervalSec(1);

            rule.configure(config);

            try {
                assertThat(rule.engine().specCount()).isGreaterThanOrEqualTo(1);
                int initialCount = rule.engine().specCount();

                // Add a second spec on disk
                writeSpec(tempDir, "spec-b");

                // Wait for at least one reload cycle
                TimeUnit.SECONDS.sleep(3);

                // Engine should now have more specs
                assertThat(rule.engine().specCount()).isGreaterThan(initialCount);
            } finally {
                rule.shutdown();
            }
        }

        @Test
        void reloadFailureRetainsPreviousRegistry(@TempDir Path tempDir) throws Exception {
            // Start with a valid spec
            writeSpec(tempDir, "good-spec");

            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setReloadIntervalSec(1);

            rule.configure(config);

            try {
                int configuredCount = rule.engine().specCount();
                assertThat(configuredCount).isGreaterThanOrEqualTo(1);

                // Write a malformed spec — next reload should catch the error
                Path badFile = tempDir.resolve("bad-spec.yaml");
                Files.writeString(badFile, "{ broken yaml: [[");

                // Wait for reload cycle
                TimeUnit.SECONDS.sleep(3);

                // Previous valid registry should be retained
                // (reload failure means old registry stays, specCount unchanged)
                assertThat(rule.engine().specCount()).isEqualTo(configuredCount);

                // Remove bad file, restore health
                Files.deleteIfExists(badFile);
            } finally {
                rule.shutdown();
            }
        }

        @Test
        void reloadWithProfileResolvesPath(@TempDir Path tempDir) throws Exception {
            // Create spec
            writeSpec(tempDir, "profiled-spec");

            // Create profiles directory and profile file
            Path profilesDir = tempDir.resolve("profiles");
            Files.createDirectories(profilesDir);
            Path profileFile = profilesDir.resolve("staging.yaml");
            Files.writeString(
                    profileFile,
                    String.join(
                            "\n",
                            "profile: staging",
                            "version: '1'",
                            "transforms:",
                            "  - spec: profiled-spec",
                            "    direction: request",
                            "    match:",
                            "      path: /**"));

            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setProfilesDir(profilesDir.toString());
            config.setActiveProfile("staging");
            config.setReloadIntervalSec(1);

            rule.configure(config);

            try {
                // Engine loaded spec + profile at configure time
                assertThat(rule.engine().specCount()).isGreaterThanOrEqualTo(1);

                // Wait for at least one reload cycle to verify profile path resolution
                TimeUnit.SECONDS.sleep(3);

                // Engine should still have specs after reload (profile resolved OK)
                assertThat(rule.engine().specCount()).isGreaterThanOrEqualTo(1);
            } finally {
                rule.shutdown();
            }
        }

        @Test
        void reloadWithNoProfilePassesNull(@TempDir Path tempDir) throws Exception {
            writeSpec(tempDir, "no-profile-spec");

            MessageTransformRule rule = new MessageTransformRule();
            MessageTransformConfig config = new MessageTransformConfig();
            config.setSpecsDir(tempDir.toString());
            config.setActiveProfile(""); // empty = no profile
            config.setReloadIntervalSec(1);

            rule.configure(config);

            try {
                assertThat(rule.engine().specCount()).isGreaterThanOrEqualTo(1);

                // Wait for reload — should succeed with null profile
                TimeUnit.SECONDS.sleep(3);

                assertThat(rule.engine().specCount()).isGreaterThanOrEqualTo(1);
            } finally {
                rule.shutdown();
            }
        }
    }
}
