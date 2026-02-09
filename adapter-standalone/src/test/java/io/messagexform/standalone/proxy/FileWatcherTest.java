package io.messagexform.standalone.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileWatcher} (T-004-36, FR-004-19).
 *
 * <p>
 * Scenarios covered:
 * <ul>
 * <li>S-004-29: Save new spec file → watcher detects → callback fired.</li>
 * <li>S-004-32: Rapid file saves → debounce → only one callback.</li>
 * <li>Disabled: {@code reload.enabled: false} → no watching.</li>
 * </ul>
 */
class FileWatcherTest {

    @TempDir
    Path watchDir;

    private FileWatcher watcher;

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) {
            watcher.stop();
        }
    }

    /**
     * S-004-29: Save a new YAML file to the watched directory → watcher detects
     * the change → callback is fired exactly once.
     */
    @Test
    void fileCreation_triggersCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger(0);

        watcher = new FileWatcher(watchDir, 100, () -> {
            callbackCount.incrementAndGet();
            latch.countDown();
        });
        watcher.start();

        // Write a new spec file — watcher should detect
        Files.writeString(watchDir.resolve("new-spec.yaml"), "id: new-spec\nversion: \"1.0.0\"");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should fire within 5 seconds");
        assertEquals(1, callbackCount.get(), "Callback should fire exactly once");
    }

    /**
     * S-004-32: Rapid successive file saves within the debounce window trigger
     * only a single callback after the debounce period elapses.
     */
    @Test
    void rapidSaves_debounced_singleCallback() throws Exception {
        // Use a longer debounce to ensure rapid writes are coalesced
        int debounceMs = 500;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger(0);

        watcher = new FileWatcher(watchDir, debounceMs, () -> {
            callbackCount.incrementAndGet();
            latch.countDown();
        });
        watcher.start();

        // Rapid successive writes — should be debounced into one callback
        for (int i = 0; i < 5; i++) {
            Files.writeString(watchDir.resolve("spec-" + i + ".yaml"), "id: spec-" + i + "\nversion: \"1.0.0\"");
            Thread.sleep(50); // 50ms apart — well within the 500ms debounce
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should fire within 5 seconds");
        // Wait a bit more to ensure no extra callbacks fire
        Thread.sleep(debounceMs + 200);
        assertEquals(1, callbackCount.get(), "Debounce should coalesce rapid saves into one callback");
    }

    /**
     * File modification (edit of existing file) also triggers the callback.
     */
    @Test
    void fileModification_triggersCallback() throws Exception {
        // Pre-create a file before the watcher starts
        Path specFile = watchDir.resolve("existing-spec.yaml");
        Files.writeString(specFile, "id: existing\nversion: \"1.0.0\"");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger(0);

        watcher = new FileWatcher(watchDir, 100, () -> {
            callbackCount.incrementAndGet();
            latch.countDown();
        });
        watcher.start();

        // Modify the existing file
        Files.writeString(specFile, "id: existing\nversion: \"2.0.0\"");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should fire on modification");
        assertEquals(1, callbackCount.get());
    }

    /**
     * File deletion also triggers the callback (spec removal should reload).
     */
    @Test
    void fileDeletion_triggersCallback() throws Exception {
        // Pre-create a file
        Path specFile = watchDir.resolve("delete-me.yaml");
        Files.writeString(specFile, "id: delete-me\nversion: \"1.0.0\"");

        CountDownLatch latch = new CountDownLatch(1);

        watcher = new FileWatcher(watchDir, 100, () -> {
            latch.countDown();
        });
        watcher.start();

        // Delete the file
        Files.delete(specFile);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should fire on deletion");
    }

    /**
     * Calling {@link FileWatcher#stop()} prevents further callbacks from being
     * fired even when files change.
     */
    @Test
    void stop_preventsCallbacks() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger(0);

        watcher = new FileWatcher(watchDir, 100, callbackCount::incrementAndGet);
        watcher.start();
        watcher.stop();

        // Write file after stop — callback should NOT fire
        Files.writeString(watchDir.resolve("after-stop.yaml"), "id: after\nversion: \"1.0.0\"");
        Thread.sleep(1000);

        assertEquals(0, callbackCount.get(), "No callbacks after stop()");
    }

    /**
     * Multiple directories can be watched simultaneously (specs + profiles).
     */
    @Test
    void multipleDirectories_bothWatched() throws Exception {
        Path specsDir = watchDir.resolve("specs");
        Path profilesDir = watchDir.resolve("profiles");
        Files.createDirectories(specsDir);
        Files.createDirectories(profilesDir);

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger callbackCount = new AtomicInteger(0);

        watcher = new FileWatcher(new Path[] {specsDir, profilesDir}, 100, () -> {
            callbackCount.incrementAndGet();
            latch.countDown();
        });
        watcher.start();

        // Write to specs dir
        Files.writeString(specsDir.resolve("spec.yaml"), "id: spec\nversion: \"1.0.0\"");

        // Wait for first callback to fire, then write to profiles dir
        Thread.sleep(500);
        Files.writeString(profilesDir.resolve("profile.yaml"), "profile: prof\nversion: \"1.0.0\"");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Both directories should trigger callbacks");
        assertTrue(callbackCount.get() >= 2, "Should get at least 2 callbacks (one per directory)");
    }
}
