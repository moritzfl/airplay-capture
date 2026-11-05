package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Converts captured ALAC/CAF files to player-friendly formats with embedded tags and artwork. */
public final class Transcoder {

    public record TrackTags(String title, String artist, String album, String genre,
                            Integer trackNumber, Integer year) {
    }

    public record Result(Map<String, String> files, String error) {
    }

    private volatile Settings settings;
    private volatile Boolean available;

    public Transcoder(Settings settings) {
        this.settings = settings;
    }

    public void updateSettings(Settings settings) {
        this.settings = settings;
        this.available = null;
    }

    public boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            Process process = new ProcessBuilder(settings.ffmpegPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            result = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            result = false;
        }
        if (!result) {
            Log.error("ffmpeg", "not available ('" + settings.ffmpegPath + "'); recordings stay as .caf");
        }
        available = result;
        return result;
    }

    /**
     * Exports the CAF recording in all configured formats.
     *
     * @param coverJpeg cover image or {@code null}
     */
    public Result export(Path caf, Path coverJpeg, TrackTags tags, String baseName, Path outputDir) {
        Map<String, String> files = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> targets = new LinkedHashMap<>();
        for (String format : settings.formats) {
            switch (format) {
                case "m4a" -> targets.put("m4a", baseName + ".m4a");
                case "alac" -> targets.put("alac", baseName + ".alac.m4a");
                case "mp3" -> targets.put("mp3", baseName + ".mp3");
                default -> {
                }
            }
        }
        if (targets.isEmpty()) {
            return new Result(files, null);
        }
        boolean hasCover = coverJpeg != null && Files.exists(coverJpeg);
        for (Map.Entry<String, String> target : targets.entrySet()) {
            String format = target.getKey();
            Path out = outputDir.resolve(target.getValue());
            List<String> command = command(caf, hasCover ? coverJpeg : null, tags, format, out);
            try {
                FfmpegRunner.Result result = FfmpegRunner.run(command);
                if (result.exitCode() == 0 && Files.exists(out) && Files.size(out) > 0) {
                    files.put(format, target.getValue());
                } else {
                    Files.deleteIfExists(out);
                    errors.add(format + ": " + result.output().strip());
                    Log.error("ffmpeg", "export to " + format + " failed: " + result.output().strip());
                }
            } catch (Exception e) {
                deleteQuietly(out);
                errors.add(format + ": " + e.getMessage());
                Log.error("ffmpeg", "export to " + format + " failed", e);
            }
        }
        return new Result(files, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private List<String> command(Path caf, Path cover, TrackTags tags, String format, Path out) {
        List<String> command = new ArrayList<>(List.of(
                settings.ffmpegPath, "-hide_banner", "-loglevel", "error", "-y", "-i", caf.toString()));
        if (cover != null) {
            command.add("-i");
            command.add(cover.toString());
        }
        command.addAll(List.of("-map", "0:a:0"));
        if (cover != null) {
            command.addAll(List.of("-map", "1:v:0"));
        }
        switch (format) {
            case "m4a" -> command.addAll(List.of(
                    "-c:a", "aac", "-b:a", settings.aacBitrateKbps + "k"));
            case "alac" -> command.addAll(List.of("-c:a", "copy"));
            case "mp3" -> {
                if ("cbr".equals(settings.mp3Mode)) {
                    command.addAll(List.of("-c:a", "libmp3lame",
                            "-b:a", settings.mp3BitrateKbps + "k"));
                } else {
                    command.addAll(List.of("-c:a", "libmp3lame",
                            "-q:a", String.valueOf(settings.mp3Quality)));
                }
            }
            default -> throw new IllegalArgumentException(format);
        }
        if (cover != null) {
            command.addAll(List.of("-c:v", "mjpeg", "-disposition:v", "attached_pic"));
        }
        if (format.equals("mp3")) {
            command.addAll(List.of("-id3v2_version", "3", "-write_id3v1", "1"));
        } else {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        addTag(command, "title", tags.title());
        addTag(command, "artist", tags.artist());
        addTag(command, "album", tags.album());
        addTag(command, "genre", tags.genre());
        addTag(command, "track", tags.trackNumber() == null ? null : String.valueOf(tags.trackNumber()));
        addTag(command, "date", tags.year() == null ? null : String.valueOf(tags.year()));
        command.add(out.toString());
        return command;
    }

    private static void addTag(List<String> command, String key, String value) {
        if (value != null && !value.isBlank()) {
            command.addAll(List.of("-metadata", key + "=" + value));
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
