package de.moritzf.airplaycapture.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Runs ffmpeg with its {@code -progress} stream and without a fixed wall-clock limit.
 *
 * <p>A long audiobook on a slow NAS can legitimately take longer than any constant, so a run is
 * only stopped when ffmpeg stops making progress, when it produces clearly more audio than
 * expected (the input is wrong and continuing would only burn time on a broken file), or when the
 * caller cancels it.
 */
public final class FfmpegRunner {

    /** A run that reports nothing for this long is treated as hung. */
    public static final long STALL_TIMEOUT_MS = 5 * 60 * 1000;

    private static final Pattern PROGRESS_LINE = Pattern.compile("^[a-z0-9_]+=\\S*$");

    /** Exit code and every output line that is not part of the progress stream. */
    public record Result(int exitCode, String output) {
    }

    /** Receives the furthest output position ffmpeg has reached. */
    @FunctionalInterface
    public interface Progress {
        void at(long encodedMs);
    }

    private FfmpegRunner() {
    }

    public static Result run(List<String> command) throws IOException, InterruptedException {
        return run(command, 0, null, null);
    }

    /**
     * @param expectedMs planned output duration; when positive, a run that produces clearly more
     *                   audio is stopped
     * @param progress   optional progress listener, called on ffmpeg's reader thread
     * @param cancelled  optional cancel signal; a cancelled run throws {@link CancellationException}
     * @throws IOException when ffmpeg stalls or overruns {@code expectedMs}
     */
    public static Result run(List<String> command, long expectedMs, Progress progress,
                             BooleanSupplier cancelled) throws IOException, InterruptedException {
        return run(command, expectedMs, progress, cancelled, STALL_TIMEOUT_MS);
    }

    static Result run(List<String> command, long expectedMs, Progress progress,
                      BooleanSupplier cancelled, long stallTimeoutMs)
            throws IOException, InterruptedException {
        List<String> full = new ArrayList<>(command.size() + 3);
        full.add(command.get(0));
        full.addAll(List.of("-nostats", "-progress", "pipe:2"));
        full.addAll(command.subList(1, command.size()));
        Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
        process.getOutputStream().close();

        StringBuilder output = new StringBuilder();
        AtomicLong encodedMs = new AtomicLong();
        AtomicLong lastProgressAt = new AtomicLong(System.currentTimeMillis());
        long overrunMs = expectedMs + Math.max(60_000, expectedMs / 10);
        // Set by whichever thread notices the problem first; checked even when ffmpeg already
        // exited, because a finished file with the wrong length is still broken.
        AtomicReference<Exception> abort = new AtomicReference<>();
        Runnable check = () -> {
            if (abort.get() != null) {
                return;
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                abort.compareAndSet(null, new CancellationException("conversion cancelled"));
            } else if (expectedMs > 0 && encodedMs.get() > overrunMs) {
                abort.compareAndSet(null, new IOException("encoder produced "
                        + describe(encodedMs.get()) + " of audio, planned " + describe(expectedMs)
                        + "; stopped to avoid a broken file"));
            } else if (System.currentTimeMillis() - lastProgressAt.get() > stallTimeoutMs) {
                abort.compareAndSet(null, new IOException("ffmpeg made no progress for "
                        + describe(stallTimeoutMs)));
            }
            if (abort.get() != null) {
                process.destroyForcibly();
            }
        };
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!PROGRESS_LINE.matcher(line).matches()) {
                        if (!line.isBlank()) {
                            synchronized (output) {
                                output.append(line).append('\n');
                            }
                        }
                        continue;
                    }
                    int equals = line.indexOf('=');
                    String key = line.substring(0, equals);
                    String value = line.substring(equals + 1);
                    if ("out_time_us".equals(key)) {
                        try {
                            long ms = Long.parseLong(value) / 1000;
                            // ffmpeg 8 reports the cover stream's time in the final block; keep
                            // the furthest audio position instead of jumping back.
                            if (ms > encodedMs.get()) {
                                encodedMs.set(ms);
                                lastProgressAt.set(System.currentTimeMillis());
                                if (progress != null) {
                                    progress.at(ms);
                                }
                                check.run();
                            }
                        } catch (NumberFormatException ignored) {
                            // ffmpeg reports "N/A" before the first frame
                        }
                    } else if ("progress".equals(key) && "end".equals(value)) {
                        // the trailer and a +faststart rewrite follow without further reports
                        lastProgressAt.set(System.currentTimeMillis());
                        if (progress != null) {
                            progress.at(Math.max(encodedMs.get(), expectedMs));
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "ffmpeg-progress");
        reader.setDaemon(true);
        reader.start();
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                check.run();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        reader.join(2000);
        check.run();
        Exception failure = abort.get();
        if (failure instanceof CancellationException cancellation) {
            throw cancellation;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        synchronized (output) {
            return new Result(process.exitValue(), output.toString());
        }
    }

    private static String describe(long ms) {
        return ms >= 60_000 ? Math.round(ms / 60_000.0) + " min" : Math.round(ms / 1000.0) + " s";
    }
}
