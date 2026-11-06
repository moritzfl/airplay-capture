package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Joins recorded chapter CAFs into one .m4b audiobook with chapter marks, tags and an optional
 * cover, using ffmpeg's concat demuxer and FFMETADATA chapter input.
 */
public final class AudiobookAssembler {

    public record Chapter(Path caf, String title, long durationMs) {}

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
        if (chapters.isEmpty()) {
            throw new IOException("no chapters to assemble");
        }
        Path work = Files.createTempDirectory(output.getParent(), ".m4b-");
        try {
            Path list = work.resolve("chapters.ffconcat");
            Path metadata = work.resolve("chapters.txt");
            List<String> concat = new ArrayList<>();
            for (Chapter chapter : chapters) {
                concat.add(
                    "file '" + chapter.caf().toAbsolutePath().toString().replace("'", "'\\''") + "'"
                );
            }
            Files.write(list, concat, StandardCharsets.UTF_8);
            Files.write(metadata, chapterMetadata(chapters), StandardCharsets.UTF_8);

            long totalMs = chapters
                .stream()
                .mapToLong(chapter -> Math.max(0, chapter.durationMs()))
                .sum();
            List<String> command = new ArrayList<>(
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
                    list.toString()
                )
            );
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

    private static List<String> chapterMetadata(List<Chapter> chapters) {
        List<String> lines = new ArrayList<>();
        lines.add(";FFMETADATA1");
        long start = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
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
        String escaped = value.replace("\\", "\\\\").replace('\r', ' ').replace('\n', ' ');
        if (escaped.startsWith(";") || escaped.startsWith("#")) {
            escaped = " " + escaped;
        }
        return escaped;
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
