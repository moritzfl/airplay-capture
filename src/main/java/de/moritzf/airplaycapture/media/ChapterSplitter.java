package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds chapter boundaries in recorded audiobook audio.
 *
 * <p>AirPlay does not transmit chapter marks, so the splitter works on the finished recording:
 * ffmpeg detects pauses, and chapter boundaries from metadata are moved into the nearest pause.
 * A single-file book can additionally be split at all sufficiently long pauses.
 */
public final class ChapterSplitter {

    public record Silence(long startMs, long endMs) {
        public long midpointMs() {
            return (startMs + endMs) / 2;
        }

        public long durationMs() {
            return endMs - startMs;
        }
    }

    public record Segment(Path file, long durationMs) {}

    /** A catalog plan with the book range inside the recording and the absolute chapter cuts. */
    public record CatalogPlan(long startMs, long endMs, List<Long> boundaries) {
        public long durationMs() {
            return endMs - startMs;
        }
    }

    /** Pauses shorter than this are not chapter boundaries. */
    public static final long MIN_CHAPTER_PAUSE_MS = 2_500;
    /** Never create chapters shorter than this, unless metadata already did. */
    public static final long MIN_CHAPTER_MS = 5 * 60 * 1_000;
    /** Move a metadata boundary at most this far to reach a pause. */
    static final long SNAP_TOLERANCE_MS = 3_000;
    /** Catalog durations may differ from the recording by this fraction (edition, intro, outro). */
    public static final double CATALOG_TOLERANCE = 0.15;
    /** Catalog boundaries may move further than sender marks, because the whole list is scaled. */
    static final long CATALOG_SNAP_TOLERANCE_MS = 8_000;
    private static final long MIN_GAP_MS = 1_000;

    private static final Pattern SILENCE_START = Pattern.compile(
        "silence_start:\\s*(-?[0-9]+(?:\\.[0-9]+)?)"
    );
    private static final Pattern SILENCE_END = Pattern.compile(
        "silence_end:\\s*(-?[0-9]+(?:\\.[0-9]+)?)"
    );

    private final String ffmpeg;

    public ChapterSplitter(String ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public List<Silence> detectSilences(Path audio, long totalMs)
        throws IOException, InterruptedException {
        List<String> command = List.of(
            ffmpeg,
            "-hide_banner",
            "-nostats",
            "-i",
            audio.toString(),
            "-af",
            "silencedetect=noise=-35dB:d=1.0",
            "-f",
            "null",
            "-"
        );
        FfmpegRunner.Result result = run(command);
        List<Silence> silences = new ArrayList<>();
        Long start = null;
        for (String line : result.output().split("\\R")) {
            Matcher startMatcher = SILENCE_START.matcher(line);
            if (startMatcher.find()) {
                start = secondsToMillis(startMatcher.group(1));
                continue;
            }
            Matcher endMatcher = SILENCE_END.matcher(line);
            if (endMatcher.find() && start != null) {
                long end = secondsToMillis(endMatcher.group(1));
                if (end > start) {
                    silences.add(new Silence(start, end));
                }
                start = null;
            }
        }
        if (start != null && totalMs > start) {
            silences.add(new Silence(start, totalMs));
        }
        return silences;
    }

    /**
     * Moves existing chapter boundaries (for example from sender metadata) into the nearest pause,
     * so a chapter never starts in the middle of a word.
     */
    public List<Long> snapToSilence(List<Long> targets, List<Silence> silences, long totalMs) {
        return snapToSilence(targets, silences, totalMs, SNAP_TOLERANCE_MS);
    }

    public List<Long> snapToSilence(
        List<Long> targets,
        List<Silence> silences,
        long totalMs,
        long toleranceMs
    ) {
        return snapToSilence(targets, silences, 0, totalMs, toleranceMs);
    }

    private List<Long> snapToSilence(
        List<Long> targets,
        List<Silence> silences,
        long startMs,
        long endMs,
        long toleranceMs
    ) {
        List<Long> boundaries = new ArrayList<>();
        long previous = startMs;
        for (long target : targets) {
            long best = target;
            long bestDistance = Long.MAX_VALUE;
            for (Silence silence : silences) {
                long candidate = silence.midpointMs();
                if (candidate <= previous + MIN_GAP_MS || candidate >= endMs - MIN_GAP_MS) {
                    continue;
                }
                long distance = Math.abs(candidate - target);
                if (distance <= toleranceMs && distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best > previous + MIN_GAP_MS && best < endMs - MIN_GAP_MS) {
                boundaries.add(best);
                previous = best;
            }
        }
        return boundaries;
    }

    /**
     * Turns catalog chapter durations into a plan for the actual recording. A recording that is
     * longer than the catalog is searched for a start offset, so an Audible announcement before
     * the book (and a trailing announcement) is trimmed instead of shifting every chapter.
     */
    public CatalogPlan catalogPlan(
        List<Long> chapterDurations,
        long totalMs,
        List<Silence> silences
    ) {
        if (chapterDurations == null || chapterDurations.size() < 2 || totalMs <= 0) {
            return new CatalogPlan(0, totalMs, List.of());
        }
        long catalogMs = 0;
        for (Long duration : chapterDurations) {
            if (duration == null || duration <= 0) {
                return new CatalogPlan(0, totalMs, List.of());
            }
            catalogMs += duration;
        }
        long extra = totalMs - catalogMs;
        long startMs = 0;
        long endMs = totalMs;
        double scale;
        if (extra > 0) {
            double ratio = totalMs / (double) catalogMs;
            if (ratio > 1.0 + CATALOG_TOLERANCE) {
                return new CatalogPlan(0, totalMs, List.of());
            }
            long trimStart = bestStart(chapterDurations, totalMs, extra, silences);
            if (trimStart >= 0) {
                startMs = trimStart;
                endMs = totalMs - (extra - startMs);
                scale = (endMs - startMs) / (double) catalogMs;
            } else {
                scale = totalMs / (double) catalogMs;
            }
        } else {
            scale = totalMs / (double) catalogMs;
            if (scale < 1.0 - CATALOG_TOLERANCE) {
                return new CatalogPlan(0, totalMs, List.of());
            }
        }
        List<Long> targets = new ArrayList<>();
        long at = 0;
        for (int i = 0; i < chapterDurations.size() - 1; i++) {
            at += chapterDurations.get(i);
            long target = startMs + Math.round(at * scale);
            if (target > startMs + MIN_GAP_MS && target < endMs - MIN_GAP_MS) {
                targets.add(target);
            }
        }
        List<Long> boundaries = snapToSilence(
            targets,
            silences,
            startMs,
            endMs,
            CATALOG_SNAP_TOLERANCE_MS
        );
        return new CatalogPlan(startMs, endMs, boundaries);
    }

    /** Convenience for callers that only need the cuts. */
    public List<Long> catalogBoundaries(
        List<Long> chapterDurations,
        long totalMs,
        List<Silence> silences
    ) {
        return catalogPlan(chapterDurations, totalMs, silences).boundaries();
    }

    /**
     * Finds the start offset that best aligns catalog boundaries with pauses. The remaining extra
     * audio is treated as a trailing announcement. Without pauses, an intro is assumed.
     */
    private long bestStart(
        List<Long> chapterDurations,
        long totalMs,
        long extra,
        List<Silence> silences
    ) {
        if (silences.isEmpty() || extra <= 0) {
            return -1;
        }
        List<Long> midpoints = silences.stream().map(Silence::midpointMs).sorted().toList();
        long catalogMs = totalMs - extra;
        double noTrimScore = scaledScore(chapterDurations, totalMs, catalogMs, midpoints);
        double zeroTrimScore = offsetScore(chapterDurations, totalMs, extra, 0, midpoints);
        long bestStart = -1;
        double bestScore = noTrimScore;
        if (zeroTrimScore > noTrimScore + 1000) {
            bestScore = zeroTrimScore;
            bestStart = 0;
        }
        long step = 500;
        for (long start = step; start <= extra; start += step) {
            double score = offsetScore(chapterDurations, totalMs, extra, start, midpoints);
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        if (bestStart < 0) {
            return -1;
        }
        if (bestStart == 0) {
            long end = totalMs - extra;
            return nearestDistance(midpoints, end) <= CATALOG_SNAP_TOLERANCE_MS ? 0 : -1;
        }
        if (bestScore < noTrimScore + 1500) {
            return -1;
        }
        long bestEnd = totalMs - (extra - bestStart);
        long startDistance = nearestDistance(midpoints, bestStart);
        long endDistance = nearestDistance(midpoints, bestEnd);
        if (startDistance > CATALOG_SNAP_TOLERANCE_MS && endDistance > CATALOG_SNAP_TOLERANCE_MS) {
            return -1;
        }
        return bestStart;
    }

    private static double scaledScore(
        List<Long> chapterDurations,
        long totalMs,
        long catalogMs,
        List<Long> midpoints
    ) {
        double scale = totalMs / (double) catalogMs;
        double score = 0;
        long at = 0;
        for (int i = 0; i < chapterDurations.size() - 1; i++) {
            at += chapterDurations.get(i);
            score += boundaryScore(Math.round(at * scale), midpoints);
        }
        return score;
    }

    private static double offsetScore(
        List<Long> chapterDurations,
        long totalMs,
        long extra,
        long start,
        List<Long> midpoints
    ) {
        long end = totalMs - (extra - start);
        double score = 0;
        long at = 0;
        for (int i = 0; i < chapterDurations.size() - 1; i++) {
            at += chapterDurations.get(i);
            score += boundaryScore(start + at, midpoints);
        }
        long startDistance = nearestDistance(midpoints, start);
        long endDistance = nearestDistance(midpoints, end);
        if (startDistance <= CATALOG_SNAP_TOLERANCE_MS) {
            score += 500;
        }
        if (endDistance <= CATALOG_SNAP_TOLERANCE_MS) {
            score += 500;
        }
        score -= Math.min(startDistance, 30_000) / 1000.0;
        score -= Math.min(endDistance, 30_000) / 1000.0;
        // Prefer a leading announcement when the evidence is otherwise equal.
        score += start / 1_000_000.0;
        return score;
    }

    private static double boundaryScore(long target, List<Long> midpoints) {
        long distance = nearestDistance(midpoints, target);
        double score = distance <= CATALOG_SNAP_TOLERANCE_MS ? 1000 : 0;
        return score - Math.min(distance, 30_000) / 1000.0;
    }

    private static long nearestDistance(List<Long> sorted, long target) {
        int index = Collections.binarySearch(sorted, target);
        if (index >= 0) {
            return 0;
        }
        int insertion = -index - 1;
        long best = Long.MAX_VALUE;
        if (insertion < sorted.size()) {
            best = Math.abs(sorted.get(insertion) - target);
        }
        if (insertion > 0) {
            best = Math.min(best, Math.abs(sorted.get(insertion - 1) - target));
        }
        return best;
    }

    /** Splits a single recording at long pauses, enforcing a minimum chapter length. */
    public List<Long> silenceBoundaries(List<Silence> silences, long totalMs) {
        List<Long> boundaries = new ArrayList<>();
        long previous = 0;
        for (Silence silence : silences) {
            if (silence.durationMs() < MIN_CHAPTER_PAUSE_MS) {
                continue;
            }
            long candidate = silence.midpointMs();
            if (candidate - previous < MIN_CHAPTER_MS) {
                continue;
            }
            if (totalMs - candidate < MIN_CHAPTER_MS) {
                continue;
            }
            boundaries.add(candidate);
            previous = candidate;
        }
        return boundaries;
    }

    /** Concatenates CAF files losslessly into one temporary CAF. */
    public Path concatenate(List<Path> files, Path output, Path workDir)
        throws IOException, InterruptedException {
        Path list = workDir.resolve("concat.txt");
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            lines.add("file '" + file.toAbsolutePath().toString().replace("'", "'\\''") + "'");
        }
        Files.write(list, lines, StandardCharsets.UTF_8);
        FfmpegRunner.Result result = run(
            List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                list.toString(),
                "-c:a",
                "pcm_s16le",
                output.toString()
            )
        );
        if (result.exitCode() != 0) {
            throw new IOException("concat failed: " + result.output().strip());
        }
        return output;
    }

    public List<Segment> split(
        Path audio,
        List<Long> boundaries,
        long totalMs,
        Path workDir,
        String prefix
    ) throws IOException, InterruptedException {
        return split(audio, 0, totalMs, boundaries, workDir, prefix);
    }

    public List<Segment> split(
        Path audio,
        long startMs,
        long endMs,
        List<Long> boundaries,
        Path workDir,
        String prefix
    ) throws IOException, InterruptedException {
        List<Long> cuts = new ArrayList<>();
        cuts.add(startMs);
        cuts.addAll(boundaries);
        cuts.add(endMs);
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < cuts.size() - 1; i++) {
            long start = cuts.get(i);
            long duration = cuts.get(i + 1) - start;
            if (duration <= 0) {
                continue;
            }
            Path segment = workDir.resolve(prefix + "-" + (i + 1) + ".caf");
            FfmpegRunner.Result result = run(
                List.of(
                    ffmpeg,
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-ss",
                    String.format(java.util.Locale.ROOT, "%.3f", start / 1000.0),
                    "-i",
                    audio.toString(),
                    "-t",
                    String.format(java.util.Locale.ROOT, "%.3f", duration / 1000.0),
                    "-map",
                    "0:a:0",
                    "-c:a",
                    "pcm_s16le",
                    segment.toString()
                )
            );
            if (result.exitCode() != 0) {
                throw new IOException("chapter split failed: " + result.output().strip());
            }
            segments.add(new Segment(segment, duration));
        }
        return segments;
    }

    private static long secondsToMillis(String value) {
        try {
            return Math.round(Double.parseDouble(value) * 1000.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static FfmpegRunner.Result run(List<String> command)
        throws IOException, InterruptedException {
        FfmpegRunner.Result result = FfmpegRunner.run(command);
        if (result.exitCode() != 0) {
            Log.warn(
                "audiobook",
                "ffmpeg failed: " + command.get(0) + " (exit " + result.exitCode() + ")"
            );
        }
        return result;
    }
}
