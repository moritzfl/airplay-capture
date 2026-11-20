package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Encodes a continuous recording or joins separate recordings into an .m4b audiobook with
 * chapter marks, tags and an optional cover.
 */
public final class AudiobookAssembler {

    public record Chapter(Path caf, String title, long durationMs) {}

    public record Marker(String title, long durationMs) {}

    /** Chapter boundaries in one continuous input, starting at the given trim offset. */
    public record Timeline(Path audio, long startMs, List<Marker> markers) {
        public long durationMs() {
            return markers.stream().mapToLong(Marker::durationMs).sum();
        }
    }

    /** Receives the encoder's own progress, 0..1. */
    @FunctionalInterface
    public interface Progress {
        void at(double fraction);
    }

    private final String ffmpeg;
    private final int bitrateKbps;

    public AudiobookAssembler(String ffmpeg, int bitrateKbps) {
        this.ffmpeg = ffmpeg;
        this.bitrateKbps = bitrateKbps;
    }

    /**
     * @param chapters existing CAF files in playback order
     * @param cover    optional cover image
     * @param output   target .m4b file
     */
    public void assemble(
        List<Chapter> chapters,
        Path cover,
        Path output,
        String title,
        String author
    ) throws IOException, InterruptedException {
        assemble(chapters, cover, output, title, author, null);
    }

    /** Same as {@link #assemble(List, Path, Path, String, String)}, reporting encode progress. */
    public void assemble(
        List<Chapter> chapters,
        Path cover,
        Path output,
        String title,
        String author,
        Progress progress
    ) throws IOException, InterruptedException {
        assemble(chapters, cover, output, title, author, progress, null);
    }

    /**
     * Encodes the audiobook, reporting progress and stopping early when {@code cancelled} turns
     * true. A cancelled or failed encode leaves no output file behind.
     */
    public void assemble(
        List<Chapter> chapters,
        Path cover,
        Path output,
        String title,
        String author,
        Progress progress,
        BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        List<Path> files = chapters.stream().map(Chapter::caf).toList();
        List<Marker> markers = chapters
            .stream()
            .map(chapter -> new Marker(chapter.title(), chapter.durationMs()))
            .toList();
        encode(files, null, markers, cover, output, title, author, progress, cancelled);
    }

    /** Writes chapter marks without splitting the input into per-chapter audio files. */
    public void assemble(
        Timeline timeline,
        Path cover,
        Path output,
        String title,
        String author,
        Progress progress,
        BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        encode(
            List.of(),
            timeline,
            timeline.markers(),
            cover,
            output,
            title,
            author,
            progress,
            cancelled
        );
    }

    private void encode(
        List<Path> files,
        Timeline timeline,
        List<Marker> markers,
        Path cover,
        Path output,
        String title,
        String author,
        Progress progress,
        BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        if (markers.isEmpty()) {
            throw new IOException("no chapters to assemble");
        }
        Path work = Files.createTempDirectory(output.getParent(), ".m4b-");
        try {
            Path metadata = work.resolve("chapters.txt");
            Files.write(metadata, chapterMetadata(markers), StandardCharsets.UTF_8);
            long totalMs = markers
                .stream()
                .mapToLong(chapter -> Math.max(0, chapter.durationMs()))
                .sum();
            List<String> command = new ArrayList<>(
                List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y")
            );
            if (timeline != null) {
                command.addAll(
                    List.of(
                        "-ss",
                        seconds(timeline.startMs()),
                        "-t",
                        seconds(totalMs),
                        "-i",
                        timeline.audio().toString()
                    )
                );
            } else {
                Path list = work.resolve("chapters.ffconcat");
                List<String> concat = files
                    .stream()
                    .map(
                        file ->
                            "file '" + file.toAbsolutePath().toString().replace("'", "'\\''") + "'"
                    )
                    .toList();
                Files.write(list, concat, StandardCharsets.UTF_8);
                command.addAll(List.of("-f", "concat", "-safe", "0", "-i", list.toString()));
            }
            int nextInput = 1;
            int coverInput = -1;
            if (cover != null && Files.exists(cover)) {
                coverInput = nextInput++;
                command.addAll(List.of("-i", cover.toString()));
            }
            int metadataInput = nextInput;
            command.addAll(List.of("-i", metadata.toString(), "-map", "0:a:0"));
            if (coverInput > 0) {
                command.addAll(List.of("-map", coverInput + ":v:0"));
            }
            command.addAll(
                List.of(
                    "-map_chapters",
                    String.valueOf(metadataInput),
                    "-c:a",
                    "aac",
                    "-b:a",
                    bitrateKbps + "k"
                )
            );
            if (coverInput > 0) {
                command.addAll(List.of("-c:v", "mjpeg", "-disposition:v", "attached_pic"));
            }
            addTag(command, "title", title);
            addTag(command, "album", title);
            addTag(command, "artist", author);
            addTag(command, "album_artist", author);
            command.addAll(
                List.of(
                    "-metadata",
                    "genre=Audiobook",
                    "-metadata",
                    "media_type=2",
                    "-movflags",
                    "+faststart",
                    "-f",
                    "ipod",
                    output.toString()
                )
            );

            FfmpegRunner.Result result;
            try {
                result = FfmpegRunner.run(
                    command,
                    totalMs,
                    progress == null
                        ? null
                        : ms -> report(progress, totalMs <= 0 ? 0 : ms / (double) totalMs),
                    cancelled
                );
            } catch (CancellationException | IOException e) {
                Files.deleteIfExists(output);
                throw e;
            }
            if (result.exitCode() != 0 || !Files.exists(output) || Files.size(output) == 0) {
                Files.deleteIfExists(output);
                throw new IOException("ffmpeg m4b failed: " + result.output().strip());
            }
        } finally {
            deleteRecursively(work);
        }
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
    }

    private static List<String> chapterMetadata(List<Marker> chapters) {
        List<String> lines = new ArrayList<>();
        lines.add(";FFMETADATA1");
        long start = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Marker chapter = chapters.get(i);
            long duration = Math.max(0, chapter.durationMs());
            if (duration == 0) {
                continue;
            }
            long end = start + duration;
            String title =
                chapter.title() == null || chapter.title().isBlank()
                    ? "Chapter " + (i + 1)
                    : chapter.title();
            lines.add("[CHAPTER]");
            lines.add("TIMEBASE=1/1000");
            lines.add("START=" + start);
            lines.add("END=" + end);
            lines.add("title=" + escape(title));
            start = end;
        }
        return lines;
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("=", "\\=")
            .replace(";", "\\;")
            .replace("#", "\\#")
            .replace('\r', ' ')
            .replace('\n', ' ');
    }

    private static void addTag(List<String> command, String key, String value) {
        if (value != null && !value.isBlank()) {
            command.addAll(List.of("-metadata", key + "=" + value));
        }
    }

    private static void report(Progress progress, double fraction) {
        if (progress != null) {
            progress.at(Math.max(0, Math.min(1, fraction)));
        }
    }

    private static void deleteRecursively(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    Log.warn("audiobook", "cannot delete " + path + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            Log.warn("audiobook", "cannot clean work dir " + directory + ": " + e.getMessage());
        }
    }
}
