package de.moritzf.airplaycapture.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChapterSplitterTest {

    @TempDir
    Path temp;

    @Test
    void movesMetadataBoundariesIntoTheNearestPause() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");
        List<ChapterSplitter.Silence> silences = List.of(
                new ChapterSplitter.Silence(598_000, 603_000),
                new ChapterSplitter.Silence(1_201_000, 1_204_000));

        List<Long> snapped = splitter.snapToSilence(List.of(600_000L, 1_200_000L), silences, 1_800_000);

        assertEquals(2, snapped.size());
        assertEquals(600_500, snapped.get(0));
        assertEquals(1_202_500, snapped.get(1));
    }

    @Test
    void ignoresPausesThatWouldCreateTinyChapters() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");
        List<ChapterSplitter.Silence> silences = List.of(
                new ChapterSplitter.Silence(60_000, 64_000),          // 1 min in: too early
                new ChapterSplitter.Silence(600_000, 604_000),        // 10 min in
                new ChapterSplitter.Silence(1_199_000, 1_201_000),    // too short
                new ChapterSplitter.Silence(1_800_000, 1_804_000));   // 30 min in

        List<Long> boundaries = splitter.silenceBoundaries(silences, 2_400_000);

        assertEquals(List.of(602_000L, 1_802_000L), boundaries);
    }

    @Test
    void scalesCatalogChaptersToTheRecordingAndSnapsThemToPauses() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");
        List<ChapterSplitter.Silence> silences = List.of(
                new ChapterSplitter.Silence(598_000, 602_000),
                new ChapterSplitter.Silence(1_201_000, 1_204_000));

        List<Long> boundaries = splitter.catalogBoundaries(
                List.of(600_000L, 600_000L, 600_000L), 1_800_000, silences);

        assertEquals(List.of(600_000L, 1_202_500L), boundaries);
    }

    @Test
    void keepsScaledCatalogBoundariesWhenThereIsNoPause() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");

        List<Long> boundaries = splitter.catalogBoundaries(
                List.of(1_500L, 1_500L), 3_000, List.of());

        assertEquals(List.of(1_500L), boundaries);
    }

    @Test
    void rejectsCatalogEditionsThatDoNotFitTheRecording() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");

        List<Long> boundaries = splitter.catalogBoundaries(
                List.of(600_000L, 600_000L), 1_800_000, List.of());

        assertTrue(boundaries.isEmpty());
    }

    @Test
    void trimsAnAudibleIntroAndOutroBeforeCuttingChapters() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");
        List<ChapterSplitter.Silence> silences = List.of(
                new ChapterSplitter.Silence(34_000, 36_000),
                new ChapterSplitter.Silence(634_000, 636_000),
                new ChapterSplitter.Silence(1_234_000, 1_236_000),
                new ChapterSplitter.Silence(1_834_000, 1_836_000));

        ChapterSplitter.CatalogPlan plan = splitter.catalogPlan(
                List.of(600_000L, 600_000L, 600_000L), 1_860_000, silences);

        assertEquals(35_000, plan.startMs());
        assertEquals(1_835_000, plan.endMs());
        assertEquals(List.of(635_000L, 1_235_000L), plan.boundaries());
    }

    @Test
    void keepsTheFullRecordingWhenThereAreNoPausesToAlignWith() {
        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");

        ChapterSplitter.CatalogPlan plan = splitter.catalogPlan(
                List.of(600_000L, 600_000L), 1_260_000, List.of());

        assertEquals(0, plan.startMs());
        assertEquals(1_260_000, plan.endMs());
        assertEquals(List.of(630_000L), plan.boundaries());
    }

    @Test
    void detectsAndSplitsAtSilence() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg required");
        Path audio = temp.resolve("book.wav");
        ffmpeg("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
                "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
                "-filter_complex", "[1:a]atrim=duration=3[s];[0:a][s][0:a]concat=n=3:v=0:a=1",
                "-c:a", "pcm_s16le", audio.toString());

        ChapterSplitter splitter = new ChapterSplitter("ffmpeg");
        List<ChapterSplitter.Silence> silences = splitter.detectSilences(audio, 13_000);
        assertFalse(silences.isEmpty(), "no silence detected");
        long middle = silences.get(0).midpointMs();
        assertTrue(middle > 5_000 && middle < 8_000, "silence at " + middle);

        List<ChapterSplitter.Segment> segments =
                splitter.split(audio, List.of(middle), 13_000, temp, "part");
        assertEquals(2, segments.size());
        for (ChapterSplitter.Segment segment : segments) {
            assertTrue(Files.size(segment.file()) > 0);
        }

        Path joined = splitter.concatenate(
                segments.stream().map(ChapterSplitter.Segment::file).toList(),
                temp.resolve("joined.wav"), temp);
        assertTrue(Files.size(joined) > 0);
        assertTrue(duration(joined) > 12.5, "concatenated duration");
    }

    private static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ffmpeg(String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "ffmpeg timeout");
        assertEquals(0, process.exitValue());
    }

    private static double duration(Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
                "format=duration", "-of", "default=nw=1:nk=1", file.toString())
                .redirectErrorStream(true).start();
        String value = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor(30, TimeUnit.SECONDS);
        return Double.parseDouble(value);
    }
}
