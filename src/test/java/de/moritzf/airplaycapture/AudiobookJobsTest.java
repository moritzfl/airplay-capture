package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.moritzf.airplaycapture.recording.AudiobookCreator;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The conversion queue: states, progress, cancellation, failure and restart behaviour. */
class AudiobookJobsTest {

    @TempDir
    Path temp;

    /** Closed after each test, so no queue write races the temp folder cleanup. */
    private final List<AudiobookJobs> opened = new ArrayList<>();

    private AudiobookJobs open(AudiobookJobs jobs) {
        opened.add(jobs);
        return jobs;
    }

    @AfterEach
    void closeJobs() {
        opened.forEach(AudiobookJobs::close);
    }

    private AppDirs dirs() {
        AppDirs dirs = new AppDirs(temp.resolve("data"));
        dirs.createDirectories();
        return dirs;
    }

    private static AudiobookCreator.Result result(String title) {
        return new AudiobookCreator.Result("t1", title + ".m4b", 1024, 3, 60_000, title, "Author");
    }

    @Test
    void reportsProgressAndKeepsTheFinishedDownload() throws Exception {
        UiState ui = new UiState();
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), ui));
        CountDownLatch release = new CountDownLatch(1);

        AudiobookJobs.Job job = jobs.enqueue("group", "Test Book", "Author", progress -> {
            progress.step("Encoding audiobook", 0.5);
            release.countDown();
            return result("Test Book");
        });
        assertTrue(release.await(10, TimeUnit.SECONDS));

        AudiobookJobs.Job done = waitFor(jobs, job.id, AudiobookJobs.State.DONE);
        assertEquals(1.0, done.progress, 0.0001);
        assertEquals("t1", done.resultTrackId);
        assertEquals("Test Book.m4b", done.resultFileName);
        assertEquals(3, done.chapterCount);
        assertEquals(1024, done.resultSizeBytes);
        assertFalse(jobs.isActive("group"));
        assertTrue(jobs.active().isEmpty());
    }

    @Test
    void exposesTheRunningStepAndFraction() throws Exception {
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), new UiState()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AudiobookJobs.Job job = jobs.enqueue("group", "Slow Book", "", progress -> {
            progress.step("Encoding audiobook at 96 kbps", 0.42);
            started.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return result("Slow Book");
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        AudiobookJobs.Job running = jobs.find(job.id).orElseThrow();
        assertEquals(AudiobookJobs.State.RUNNING, running.status());
        assertEquals("Encoding audiobook at 96 kbps", running.step);
        assertEquals(0.42, running.progress, 0.0001);
        assertEquals("converting", running.badge());
        assertTrue(jobs.isActive("group"));
        finish.countDown();
        waitFor(jobs, job.id, AudiobookJobs.State.DONE);
    }

    @Test
    void queuedJobsCanBeCancelledBeforeTheyRun() throws Exception {
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), new UiState()));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        jobs.enqueue("first", "First Book", "", progress -> {
            running.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return result("First Book");
        });
        assertTrue(running.await(10, TimeUnit.SECONDS));

        AudiobookJobs.Job second = jobs.enqueue("second", "Second Book", "", progress -> {
            throw new AssertionError("a cancelled job must not run");
        });
        assertEquals(AudiobookJobs.State.QUEUED, second.status());
        assertTrue(jobs.cancel(second.id));
        assertEquals(AudiobookJobs.State.CANCELLED, jobs.find(second.id).orElseThrow().status());
        assertFalse(jobs.cancel(second.id), "cancelling twice is a no-op");

        finish.countDown();
        waitFor(jobs, second.id, AudiobookJobs.State.CANCELLED);
    }

    @Test
    void runningJobsCanBeCancelled() throws Exception {
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), new UiState()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AudiobookJobs.Job job = jobs.enqueue("group", "Long Book", "", progress -> {
            progress.step("Encoding audiobook", 0.5);
            started.countDown();
            // hold the task until the test has looked at the "Cancelling…" state
            assertTrue(stop.await(10, TimeUnit.SECONDS));
            assertTrue(progress.cancelled(), "the task sees the cancel request");
            throw new java.util.concurrent.CancellationException("conversion cancelled");
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        assertTrue(jobs.cancel(job.id));
        assertEquals("Cancelling…", jobs.find(job.id).orElseThrow().step);
        assertFalse(jobs.cancel(job.id), "a second cancel is a no-op");
        stop.countDown();

        AudiobookJobs.Job cancelled = waitFor(jobs, job.id, AudiobookJobs.State.CANCELLED);
        assertNull(cancelled.error, "a cancel is not an error");
        assertFalse(jobs.isActive("group"));
    }

    @Test
    void aSuccessfulBuildClearsEarlierFailedAttemptsOfTheSameBook() throws Exception {
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), new UiState()));
        AudiobookJobs.Job failed = jobs.enqueue("book", "Book", "", progress -> {
            throw new java.io.IOException("disk full");
        });
        waitFor(jobs, failed.id, AudiobookJobs.State.FAILED);
        AudiobookJobs.Job other = jobs.enqueue("other", "Other", "", progress -> {
            throw new java.io.IOException("disk full");
        });
        waitFor(jobs, other.id, AudiobookJobs.State.FAILED);

        AudiobookJobs.Job retry = jobs.enqueue("book", "Book", "", progress -> result("Book"));
        waitFor(jobs, retry.id, AudiobookJobs.State.DONE);

        assertTrue(jobs.find(failed.id).isEmpty(), "the answered failure is gone");
        assertTrue(jobs.find(other.id).isPresent(), "other books keep their failures");
    }

    @Test
    void failuresAreKeptWithTheirReason() throws Exception {
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), new UiState()));
        AudiobookJobs.Job job = jobs.enqueue("group", "Broken Book", "", progress -> {
            throw new java.io.IOException("ffmpeg m4b failed: disk full");
        });
        AudiobookJobs.Job failed = waitFor(jobs, job.id, AudiobookJobs.State.FAILED);
        assertEquals("ffmpeg m4b failed: disk full", failed.error);
        assertEquals("failed", failed.badge());
        assertFalse(jobs.isActive("group"));
    }

    @Test
    void queueSurvivesARestartAndInterruptedJobsAreMarked() throws Exception {
        AppDirs dirs = dirs();
        AudiobookJobs first = open(new AudiobookJobs(dirs, new UiState()));
        AudiobookJobs.Job done = first.enqueue("group", "Finished Book", "Author", progress ->
            result("Finished Book")
        );
        waitFor(first, done.id, AudiobookJobs.State.DONE);
        // The worker writes the queue once more after a job finishes; a stopped process does not.
        first.close();

        // Simulate a job that was running when the process died.
        AudiobookJobs.Job running = new AudiobookJobs.Job();
        running.id = "interrupted";
        running.groupKey = "other";
        running.title = "Interrupted Book";
        running.state = AudiobookJobs.State.RUNNING.name();
        String json = java.nio.file.Files.readString(first.jobsFile());
        String merged = json.replaceFirst(
            "\\]\\s*$",
            ",\n  {\n    \"id\": \"interrupted\",\n" +
                "    \"groupKey\": \"other\",\n    \"title\": \"Interrupted Book\",\n" +
                "    \"state\": \"RUNNING\",\n    \"step\": \"Encoding\",\n" +
                "    \"createdAtEpochMs\": 1\n  }\n]"
        );
        java.nio.file.Files.writeString(first.jobsFile(), merged);

        AudiobookJobs restarted = open(new AudiobookJobs(dirs, new UiState()));
        List<AudiobookJobs.Job> loaded = restarted.jobs();
        assertEquals(2, loaded.size());
        AudiobookJobs.Job finished = restarted.find(done.id).orElseThrow();
        assertEquals(AudiobookJobs.State.DONE, finished.status());
        assertEquals("Finished Book.m4b", finished.resultFileName);
        AudiobookJobs.Job interrupted = restarted.find("interrupted").orElseThrow();
        assertEquals(AudiobookJobs.State.FAILED, interrupted.status());
        assertNotNull(interrupted.error);
        assertTrue(restarted.active().isEmpty());
    }

    @Test
    void historyCanBeClearedButActiveJobsStay() throws Exception {
        AudiobookJobs jobs = open(new AudiobookJobs(dirs(), new UiState()));
        AudiobookJobs.Job done = jobs.enqueue("a", "Done Book", "", progress -> result("Done"));
        waitFor(jobs, done.id, AudiobookJobs.State.DONE);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AudiobookJobs.Job running = jobs.enqueue("b", "Running Book", "", progress -> {
            started.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return result("Running");
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        assertEquals(1, jobs.clearFinished());
        assertTrue(jobs.find(done.id).isEmpty());
        assertEquals(AudiobookJobs.State.RUNNING, jobs.find(running.id).orElseThrow().status());
        finish.countDown();
        waitFor(jobs, running.id, AudiobookJobs.State.DONE);
    }

    private static AudiobookJobs.Job waitFor(
        AudiobookJobs jobs,
        String id,
        AudiobookJobs.State state
    ) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            AudiobookJobs.Job job = jobs.find(id).orElseThrow();
            if (job.status() == state) {
                return job;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(
            "job " +
                id +
                " did not reach " +
                state +
                ": " +
                jobs
                    .find(id)
                    .map(job -> job.state + " / " + job.step)
                    .orElse("gone")
        );
    }
}
