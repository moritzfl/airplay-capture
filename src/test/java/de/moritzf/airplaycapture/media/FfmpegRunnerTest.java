package de.moritzf.airplaycapture.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegRunnerTest {

    @TempDir
    Path temp;

    @Test
    void aSlowRunThatKeepsProgressingIsNotStopped() throws Exception {
        // Runs for ~2 s against a 500 ms stall limit: only missing progress may end a run.
        Path ffmpeg = script(
            """
            i=1
            while [ $i -le 10 ]; do
              echo "out_time_us=${i}000000" >&2
              echo "progress=continue" >&2
              sleep 0.2
              i=$((i + 1))
            done
            echo "real message" >&2
            echo "progress=end" >&2
            """
        );
        AtomicLong last = new AtomicLong();

        FfmpegRunner.Result result = FfmpegRunner.run(
            List.of(ffmpeg.toString(), "-i", "x"),
            10_000,
            last::set,
            null,
            500
        );

        assertEquals(0, result.exitCode());
        assertEquals("real message", result.output().strip());
        assertEquals(10_000, last.get());
    }

    @Test
    void aHungRunIsStoppedAfterTheStallLimit() throws Exception {
        Path ffmpeg = script(
            """
            echo "out_time_us=1000000" >&2
            sleep 30
            """
        );

        long started = System.currentTimeMillis();
        IOException error = assertThrows(IOException.class, () ->
            FfmpegRunner.run(List.of(ffmpeg.toString()), 0, null, null, 500)
        );

        assertTrue(error.getMessage().contains("no progress"), error.getMessage());
        assertTrue(System.currentTimeMillis() - started < 10_000, "stall must stop the run early");
    }

    @Test
    void cancellingStopsTheRun() throws Exception {
        Path ffmpeg = script("sleep 30\n");

        long started = System.currentTimeMillis();
        assertThrows(CancellationException.class, () ->
            FfmpegRunner.run(List.of(ffmpeg.toString()), 0, null, () -> true, 60_000)
        );

        assertTrue(
            System.currentTimeMillis() - started < 10_000,
            "cancel must not wait for ffmpeg"
        );
    }

    @Test
    void passesTheProgressOptionsBeforeTheCallerArguments() throws Exception {
        Path ffmpeg = script("echo \"$@\" >&2\n");

        FfmpegRunner.Result result = FfmpegRunner.run(List.of(ffmpeg.toString(), "-i", "in.caf"));

        assertEquals("-nostats -progress pipe:2 -i in.caf", result.output().strip());
        assertFalse(result.output().contains("progress="));
    }

    private Path script(String body) throws IOException {
        Path file = temp.resolve("ffmpeg-" + System.nanoTime() + ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }
}
