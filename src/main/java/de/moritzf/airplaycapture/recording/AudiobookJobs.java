package de.moritzf.airplaycapture.recording;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.UiState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable queue of audiobook conversions.
 *
 * <p>Every conversion the user starts becomes a job with a user-facing state
 * ({@code queued}, {@code running}, {@code done}, {@code failed}, {@code cancelled}), a step
 * label, a progress fraction and, once finished, the resulting library entry. Jobs are written to
 * {@code audiobook-jobs.json} so the queue and its history survive a restart, and every change is
 * pushed to the UI through {@link UiState}.
 *
 * <p>One worker runs one job at a time; queued jobs can be cancelled before they start.
 */
public final class AudiobookJobs {

    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();
    /** Finished jobs kept in the history; older ones are dropped from the file. */
    private static final int HISTORY_LIMIT = 40;
    /** Progress is persisted at most this often; state changes are always written at once. */
    private static final long SAVE_INTERVAL_MS = 5_000;

    public enum State {
        QUEUED,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
    }

    /** One conversion, as shown in the queue panel. */
    public static final class Job {

        public String id;
        public String groupKey;
        public String title;
        public String author;
        public String state = State.QUEUED.name();
        public String step = "Waiting in queue";
        public double progress;
        public int chapterCount;
        public long durationMs;
        public String resultTrackId;
        public String resultFileName;
        public long resultSizeBytes;
        public String error;
        public long createdAtEpochMs = System.currentTimeMillis();
        public long startedAtEpochMs;
        public long finishedAtEpochMs;

        public State status() {
            try {
                return State.valueOf(state);
            } catch (RuntimeException e) {
                return State.QUEUED;
            }
        }

        public boolean active() {
            State status = status();
            return status == State.QUEUED || status == State.RUNNING;
        }

        /**
         * Copy for the UI so a later mutation cannot change what a view already rendered. Takes the
         * lock the worker holds while it updates a job, so a copy never shows a finished job
         * without its result.
         */
        public synchronized Job copy() {
            Job copy = new Job();
            copy.id = id;
            copy.groupKey = groupKey;
            copy.title = title;
            copy.author = author;
            copy.state = state;
            copy.step = step;
            copy.progress = progress;
            copy.chapterCount = chapterCount;
            copy.durationMs = durationMs;
            copy.resultTrackId = resultTrackId;
            copy.resultFileName = resultFileName;
            copy.resultSizeBytes = resultSizeBytes;
            copy.error = error;
            copy.createdAtEpochMs = createdAtEpochMs;
            copy.startedAtEpochMs = startedAtEpochMs;
            copy.finishedAtEpochMs = finishedAtEpochMs;
            return copy;
        }

        /** Short label for the library row badge. */
        public String badge() {
            return switch (status()) {
                case QUEUED -> "queued";
                case RUNNING -> "converting";
                case DONE -> "ready";
                case FAILED -> "failed";
                case CANCELLED -> "cancelled";
            };
        }
    }

    /** Runs one conversion and reports progress; returns the finished library entry. */
    @FunctionalInterface
    public interface Task {
        AudiobookCreator.Result run(Progress progress) throws Exception;
    }

    /** Progress sink handed to the running task. */
    public interface Progress {
        void step(String label, double fraction);

        boolean cancelled();
    }

    private final AppDirs dirs;
    private final UiState ui;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelRequests = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audiobook-queue");
        thread.setDaemon(true);
        return thread;
    });

    public AudiobookJobs(AppDirs dirs, UiState ui) {
        this.dirs = dirs;
        this.ui = ui;
        load();
    }

    public Path jobsFile() {
        return dirs.dataDir().resolve("audiobook-jobs.json");
    }

    /** Newest first. */
    public List<Job> jobs() {
        List<Job> list = new ArrayList<>();
        for (Job job : jobs.values()) {
            list.add(job.copy());
        }
        list.sort((a, b) -> Long.compare(b.createdAtEpochMs, a.createdAtEpochMs));
        return list;
    }

    public List<Job> active() {
        List<Job> list = new ArrayList<>();
        for (Job job : jobs()) {
            if (job.active()) {
                list.add(job);
            }
        }
        list.sort((a, b) -> Long.compare(a.createdAtEpochMs, b.createdAtEpochMs));
        return list;
    }

    /** The newest job for a library group, including finished ones. */
    public Optional<Job> latest(String groupKey) {
        Job best = null;
        for (Job job : jobs.values()) {
            if (job.groupKey == null || !job.groupKey.equals(groupKey)) {
                continue;
            }
            if (best == null || job.createdAtEpochMs > best.createdAtEpochMs) {
                best = job;
            }
        }
        return Optional.ofNullable(best).map(Job::copy);
    }

    public Optional<Job> find(String id) {
        Job job = id == null ? null : jobs.get(id);
        return job == null ? Optional.empty() : Optional.of(job.copy());
    }

    public boolean isActive(String groupKey) {
        for (Job job : jobs.values()) {
            if (job.active() && job.groupKey != null && job.groupKey.equals(groupKey)) {
                return true;
            }
        }
        return false;
    }

    /** Adds a job to the queue and notifies the UI. */
    public Job enqueue(String groupKey, String title, String author, Task task) {
        Job job = new Job();
        job.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        job.groupKey = groupKey;
        job.title = title == null || title.isBlank() ? "Untitled audiobook" : title.trim();
        job.author = author == null ? "" : author.trim();
        jobs.put(job.id, job);
        save();
        Log.info("audiobook", "queued: " + job.title + " (" + job.id + ")");
        ui.changed();
        worker.submit(() -> execute(job.id, task));
        return job.copy();
    }

    /**
     * Cancels a job. A queued job is dropped immediately; a running one stops at the next step
     * or, while encoding, within a second, and its partial file is removed.
     */
    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return false;
        }
        synchronized (job) {
            if (job.status() == State.QUEUED) {
                job.state = State.CANCELLED.name();
                job.step = "Cancelled before it started";
                job.finishedAtEpochMs = System.currentTimeMillis();
            } else if (job.status() == State.RUNNING) {
                if (cancelRequests.putIfAbsent(id, Boolean.TRUE) != null) {
                    return false;
                }
                job.step = "Cancelling…";
            } else {
                return false;
            }
        }
        cancelRequests.put(id, Boolean.TRUE);
        save();
        Log.info("audiobook", "cancelled: " + job.title + " (" + id + ")");
        ui.changed();
        return true;
    }

    /** Removes finished jobs from the history. */
    public int clearFinished() {
        int removed = 0;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (!job.active() && jobs.remove(job.id) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            save();
            ui.changed();
        }
        return removed;
    }

    public boolean remove(String id) {
        Job job = jobs.get(id);
        if (job == null || job.active()) {
            return false;
        }
        jobs.remove(id);
        save();
        ui.changed();
        return true;
    }

    private void execute(String id, Task task) {
        Job job = jobs.get(id);
        if (job == null) {
            return;
        }
        synchronized (job) {
            if (job.status() != State.QUEUED) {
                cancelRequests.remove(id);
                return; // cancelled while waiting
            }
            job.state = State.RUNNING.name();
            job.startedAtEpochMs = System.currentTimeMillis();
            job.step = "Preparing";
            job.progress = 0.01;
        }
        save();
        ui.changed();
        try {
            AudiobookCreator.Result result = task.run(
                new Progress() {
                    private long lastSavedAt;

                    @Override
                    public void step(String label, double fraction) {
                        boolean visible;
                        synchronized (job) {
                            if (cancelRequests.containsKey(id)) {
                                return; // keep "Cancelling…" until the task stops
                            }
                            double value = Math.max(0, Math.min(1, fraction));
                            // ffmpeg reports twice a second; only whole percents or a new step are
                            // worth a repaint in every open browser.
                            visible =
                                !label.equals(job.step) ||
                                Math.round(value * 100) != Math.round(job.progress * 100);
                            job.step = label;
                            job.progress = value;
                        }
                        if (!visible) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastSavedAt >= SAVE_INTERVAL_MS) {
                            lastSavedAt = now;
                            save();
                        }
                        ui.changed();
                    }

                    @Override
                    public boolean cancelled() {
                        return cancelRequests.containsKey(id);
                    }
                }
            );
            synchronized (job) {
                job.state = State.DONE.name();
                job.progress = 1;
                job.step = "Ready to download";
                job.chapterCount = result.chapterCount();
                job.durationMs = result.durationMs();
                job.resultTrackId = result.trackId();
                job.resultFileName = result.fileName();
                job.resultSizeBytes = result.sizeBytes();
                job.finishedAtEpochMs = System.currentTimeMillis();
            }
            // A successful build answers earlier failed or cancelled attempts for the same book;
            // their "Try again" would now only mislead.
            jobs.values().removeIf(
                other ->
                    other != job &&
                    job.groupKey != null &&
                    job.groupKey.equals(other.groupKey) &&
                    (other.status() == State.FAILED || other.status() == State.CANCELLED)
            );
            Log.info("audiobook", "ready: " + job.title + " (" + result.fileName() + ")");
        } catch (Exception e) {
            if (
                e instanceof java.util.concurrent.CancellationException ||
                cancelRequests.containsKey(id)
            ) {
                synchronized (job) {
                    job.state = State.CANCELLED.name();
                    job.step = "Cancelled · source recordings are unchanged";
                    job.error = null;
                    job.finishedAtEpochMs = System.currentTimeMillis();
                }
                Log.info("audiobook", "cancelled while running: " + job.title + " (" + id + ")");
                return;
            }
            synchronized (job) {
                job.state = State.FAILED.name();
                job.step = "Conversion failed";
                job.error =
                    e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                job.finishedAtEpochMs = System.currentTimeMillis();
            }
            Log.error("audiobook", "cannot create audiobook: " + job.error);
            ui.lastError = "audiobook creation failed: " + job.error;
        } finally {
            cancelRequests.remove(id);
            prune();
            save();
            ui.changed();
        }
    }

    private void prune() {
        List<Job> finished = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (!job.active()) {
                finished.add(job);
            }
        }
        if (finished.size() <= HISTORY_LIMIT) {
            return;
        }
        finished.sort((a, b) -> Long.compare(b.createdAtEpochMs, a.createdAtEpochMs));
        for (int i = HISTORY_LIMIT; i < finished.size(); i++) {
            jobs.remove(finished.get(i).id);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(dirs.dataDir());
            Files.writeString(jobsFile(), JSON.writeValueAsString(jobs()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("audiobook", "cannot save queue: " + e.getMessage());
        }
    }

    private void load() {
        Path file = jobsFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<Job> loaded = JSON.readValue(
                Files.readString(file, StandardCharsets.UTF_8),
                new TypeReference<List<Job>>() {}
            );
            if (loaded == null) {
                return;
            }
            for (Job job : loaded) {
                if (job == null || job.id == null) {
                    continue;
                }
                if (job.active()) {
                    // The worker does not survive a restart: keep the entry, but be honest about it.
                    job.state = State.FAILED.name();
                    job.step = "Interrupted by a restart";
                    job.error = "The recorder stopped before this conversion finished.";
                    job.finishedAtEpochMs = System.currentTimeMillis();
                }
                jobs.put(job.id, job);
            }
        } catch (Exception e) {
            Log.warn("audiobook", "cannot read queue: " + e.getMessage());
        }
    }

    /** Distinct group keys that currently have an active job. */
    public Set<String> activeGroupKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Job job : jobs.values()) {
            if (job.active() && job.groupKey != null) {
                keys.add(job.groupKey);
            }
        }
        return keys;
    }
}
