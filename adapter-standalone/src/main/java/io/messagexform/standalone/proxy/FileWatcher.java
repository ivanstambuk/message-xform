package io.messagexform.standalone.proxy;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-system watcher for hot reload (IMPL-004-04, FR-004-19).
 *
 * <p>
 * Watches one or more directories for file create/modify/delete events using
 * {@link WatchService}. When a change is detected, it fires the provided
 * {@code reloadCallback} after a configurable debounce period. Rapid successive
 * changes are coalesced into a single callback invocation.
 *
 * <p>
 * Lifecycle:
 * <ol>
 * <li>{@link #start()} — begins watching on a daemon thread.</li>
 * <li>{@link #stop()} — closes the watch service and shuts down the
 * scheduler.</li>
 * </ol>
 *
 * <p>
 * Thread safety: this class is thread-safe. The watcher thread and debounce
 * scheduler are independent. The callback is invoked on the scheduler thread.
 */
public final class FileWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(FileWatcher.class);

    private final Path[] watchDirs;
    private final int debounceMs;
    private final Runnable reloadCallback;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pendingReload;

    /**
     * Creates a watcher for a single directory.
     *
     * @param watchDir       directory to watch
     * @param debounceMs     debounce period in milliseconds (CFG-004-33)
     * @param reloadCallback callback to invoke when a change is detected
     */
    public FileWatcher(Path watchDir, int debounceMs, Runnable reloadCallback) {
        this(new Path[] {watchDir}, debounceMs, reloadCallback);
    }

    /**
     * Creates a watcher for multiple directories.
     *
     * @param watchDirs      directories to watch
     * @param debounceMs     debounce period in milliseconds (CFG-004-33)
     * @param reloadCallback callback to invoke when a change is detected
     */
    public FileWatcher(Path[] watchDirs, int debounceMs, Runnable reloadCallback) {
        this.watchDirs = watchDirs;
        this.debounceMs = debounceMs;
        this.reloadCallback = reloadCallback;
    }

    /**
     * Starts watching for file changes on a daemon thread.
     *
     * @throws IOException if the watch service cannot be created or a directory
     *                     cannot be registered
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            LOG.warn("FileWatcher already running");
            return;
        }

        watchService = FileSystems.getDefault().newWatchService();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-watcher-debounce");
            t.setDaemon(true);
            return t;
        });

        for (Path dir : watchDirs) {
            dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            LOG.info("Watching directory: {}", dir);
        }

        watchThread = new Thread(this::pollLoop, "file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        LOG.info("FileWatcher started (debounce={}ms, dirs={})", debounceMs, watchDirs.length);
    }

    /**
     * Stops watching and releases all resources.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            LOG.warn("Error closing WatchService", e);
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        if (watchThread != null) {
            watchThread.interrupt();
        }
        LOG.info("FileWatcher stopped");
    }

    /**
     * Returns whether the watcher is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Main polling loop — blocks on {@link WatchService#take()} and schedules
     * the debounced reload callback when events arrive.
     */
    private void pollLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            // Drain all pending events (we only care that *something* changed)
            boolean hasRelevantEvent = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    // Overflow means events were lost — trigger reload anyway
                    hasRelevantEvent = true;
                    continue;
                }

                Path changed = (Path) event.context();
                LOG.debug("File change detected: {} ({})", changed, kind.name());
                hasRelevantEvent = true;
            }

            // Reset the key — if the directory is no longer valid, stop watching it
            boolean valid = key.reset();
            if (!valid) {
                LOG.warn("Watch key no longer valid (directory deleted?)");
            }

            if (hasRelevantEvent) {
                scheduleReload();
            }
        }
    }

    /**
     * Schedules (or reschedules) the reload callback after the debounce period.
     * If a reload is already pending, it is cancelled and a new one is
     * scheduled — effectively coalescing rapid changes into a single reload.
     */
    private synchronized void scheduleReload() {
        if (pendingReload != null && !pendingReload.isDone()) {
            pendingReload.cancel(false);
            LOG.debug("Debounce: cancelled pending reload, rescheduling");
        }

        pendingReload = scheduler.schedule(
                () -> {
                    LOG.info("Debounce elapsed — executing reload callback");
                    try {
                        reloadCallback.run();
                    } catch (Exception e) {
                        LOG.error("Reload callback failed", e);
                    }
                },
                debounceMs,
                TimeUnit.MILLISECONDS);
    }
}
