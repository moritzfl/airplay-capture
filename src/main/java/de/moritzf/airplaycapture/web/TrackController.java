package de.moritzf.airplaycapture.web;

import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.config.SettingsService;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** JSON API used by integrations and by the browser's media elements. */
@RestController
@RequestMapping("/api")
public class TrackController {

    private final Library library;
    private final UiState ui;
    private final SettingsService settingsService;
    private final AudiobookCreator audiobookCreator;

    public TrackController(Library library, UiState ui, SettingsService settingsService,
                           AudiobookCreator audiobookCreator) {
        this.library = library;
        this.ui = ui;
        this.settingsService = settingsService;
        this.audiobookCreator = audiobookCreator;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>(ui.snapshot());
        status.put("tracks", library.tracks().size());
        status.put("version", "2.0.0");
        return status;
    }

    @GetMapping("/tracks")
    public Map<String, Object> tracks() {
        List<Map<String, Object>> items = new ArrayList<>();
        long totalBytes = 0;
        for (LibraryTrack track : library.tracks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", track.id);
            item.put("title", track.title);
            item.put("artist", track.artist);
            item.put("album", track.album);
            item.put("genre", track.genre);
            item.put("durationMs", track.durationMs);
            item.put("recordedAt", track.recordedAtEpochMs);
            item.put("baseName", track.baseName);
            item.put("files", track.files);
            item.put("sizeBytes", track.sizeBytes);
            item.put("cover", track.cover);
            item.put("status", track.status);
            item.put("error", track.error);
            item.put("source", track.source);
            item.put("gaps", track.gaps);
            item.put("expectedDurationMs", track.expectedDurationMs);
            item.put("incomplete", track.incomplete);
            item.put("gapPackets", track.gapPackets);
            item.put("filledPackets", track.filledPackets);
            item.put("recoveredGaps", track.recoveredGaps);
            item.put("maxRecoveryMs", track.maxRecoveryMs);
            item.put("mediaKind", track.mediaKind);
            item.put("audiobookChapter", track.audiobookChapter);
            item.put("bookId", track.bookId);
            item.put("chapterCount", track.chapterCount);
            item.put("metadataSource", track.metadataSource);
            items.add(item);
            totalBytes += track.sizeBytes;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tracks", items);
        response.put("count", items.size());
        response.put("totalBytes", totalBytes);
        return response;
    }

    @DeleteMapping("/tracks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Optional<LibraryTrack> track = library.find(id);
        if (track.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        library.delete(track.get());
        Log.info("library", "deleted " + track.get().displayName());
        ui.changed();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tracks/{id}/cover")
    public ResponseEntity<Resource> cover(@PathVariable String id) throws IOException {
        Optional<LibraryTrack> track = library.find(id);
        if (track.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path jpeg = library.coverFile(id, false);
        Path png = library.coverFile(id, true);
        Path file = Files.exists(jpeg) ? jpeg : png;
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = file.equals(png) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(new FileSystemResource(file));
    }

    @GetMapping("/now/cover")
    public ResponseEntity<Resource> nowCover() {
        Path jpeg = library.coverFile("now", false);
        Path png = library.coverFile("now", true);
        Path file = Files.exists(jpeg) ? jpeg : png;
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = file.equals(png) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new FileSystemResource(file));
    }

    @GetMapping("/tracks/{id}/file/{format}")
    public void file(@PathVariable String id, @PathVariable String format,
                     @RequestParam(value = "download", required = false) String download,
                     @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                     HttpServletResponse response) throws IOException {
        Optional<LibraryTrack> track = library.find(id);
        if (track.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Path path = library.file(track.get(), format);
        if (path == null || !Files.exists(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String contentType = switch (format) {
            case "mp3" -> "audio/mpeg";
            case "m4a", "m4b", "alac" -> "audio/mp4";
            case "caf" -> "audio/x-caf";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
        // Browsers (especially Safari) derive the saved extension from the MIME type of an inline
        // response, which turned .m4b audiobooks into .m4a/.mp4 downloads. An explicit attachment
        // filename keeps the real container extension and the library's readable base name.
        stream(path, contentType, path.getFileName().toString(), download != null,
                rangeHeader, response);
    }

    @GetMapping("/audiobook-preview/{previewId}/{index}")
    public void audiobookPreview(@PathVariable String previewId, @PathVariable int index,
                                 @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                 String rangeHeader,
                                 HttpServletResponse response) throws IOException {
        Path clip;
        try {
            clip = audiobookCreator.previewClip(previewId, index);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (clip == null || !Files.exists(clip)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        stream(clip, "audio/mp4", "chapter-preview.m4a", false, rangeHeader, response);
    }

    @GetMapping("/audiobook-preview/{previewId}/{index}/full")
    public void audiobookFullChapter(@PathVariable String previewId, @PathVariable int index,
                                     @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                     String rangeHeader,
                                     HttpServletResponse response) throws IOException {
        Path file;
        try {
            file = audiobookCreator.fullChapter(previewId, index);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (file == null || !Files.exists(file)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String contentType = name.endsWith(".mp3") ? "audio/mpeg"
                : name.endsWith(".caf") ? "audio/x-caf" : "audio/mp4";
        stream(file, contentType, "chapter.m4a", false, rangeHeader, response);
    }

    private static void stream(Path path, String contentType, String fileName, boolean attachment,
                               String rangeHeader, HttpServletResponse response) throws IOException {
        long length = Files.size(path);
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        ContentDisposition disposition = (attachment
                ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());

        long start = 0;
        long end = length - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] spec = rangeHeader.substring(6).split("-", 2);
            try {
                start = spec[0].isBlank() ? 0 : Long.parseLong(spec[0].trim());
                end = spec.length > 1 && !spec[1].isBlank() ? Long.parseLong(spec[1].trim()) : length - 1;
                if (start < 0 || start >= length) {
                    start = 0;
                }
                end = Math.min(end, length - 1);
                partial = end >= start;
            } catch (NumberFormatException e) {
                partial = false;
            }
        }
        long count = partial ? end - start + 1 : length;
        if (partial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        }
        response.setContentLengthLong(count);
        try (InputStream in = Files.newInputStream(path)) {
            in.skipNBytes(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = count;
            OutputStream out = response.getOutputStream();
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    @GetMapping("/groups")
    public Map<String, Object> groups() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (LibraryGroups.Group group : LibraryGroups.groups(library.tracks())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", group.key());
            item.put("title", group.title());
            item.put("artist", group.artist());
            item.put("audiobook", group.isAudiobook());
            item.put("assembled", group.hasM4b());
            item.put("duplicates", group.duplicateCount());
            item.put("trackIds", group.tracks().stream().map(track -> track.id).toList());
            item.put("formats", group.formats(true));
            item.put("durationMs", group.durationMs());
            item.put("sizeBytes", group.sizeBytes());
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groups", items);
        response.put("count", items.size());
        return response;
    }

    @GetMapping("/groups/{key}/zip/{format}")
    public void groupZip(@PathVariable String key, @PathVariable String format,
                         @RequestParam(value = "all", required = false) String all,
                         HttpServletResponse response) throws IOException {
        Optional<LibraryGroups.Group> found = LibraryGroups.find(library.tracks(), key);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        LibraryGroups.Group group = found.get();
        boolean everyTake = "1".equals(all) || "true".equalsIgnoreCase(all);
        List<LibraryTrack> selected = everyTake ? group.tracks() : group.bestTakes();
        List<Path> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (LibraryTrack track : selected) {
            Path path = library.file(track, format);
            if (path != null && Files.exists(path)) {
                files.add(path);
                names.add(path.getFileName().toString());
            }
        }
        if (files.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String filename = zipName(group.title(), format) + ".zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build().toString());

        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(),
                StandardCharsets.UTF_8)) {
            for (int i = 0; i < files.size(); i++) {
                String name = names.get(i);
                String entry = name;
                int counter = 2;
                while (!used.add(entry)) {
                    entry = name.replaceFirst("(\\.[^.]+)$", " (" + counter + ")$1");
                    counter++;
                }
                zip.putNextEntry(new ZipEntry(entry));
                Files.copy(files.get(i), zip);
                zip.closeEntry();
            }
        }
    }

    private static String zipName(String title, String format) {
        String clean = title == null ? "" : title
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() > 100) {
            clean = clean.substring(0, 100).trim();
        }
        if (clean.isEmpty()) {
            clean = "recordings";
        }
        return clean + " - " + format.toUpperCase(Locale.ROOT);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return settingsView(settingsService.current());
    }

    @PutMapping("/settings")
    public ResponseEntity<?> settings(@RequestBody Settings incoming) {
        try {
            Settings applied = settingsService.apply(incoming);
            return ResponseEntity.ok(settingsView(applied));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private Map<String, Object> settingsView(Settings settings) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("settings", settings);
        map.put("libraryDir", library.libraryDir().toString());
        map.put("ffmpegAvailable", ui.ffmpegAvailable);
        map.put("formatsAvailable", List.of("m4a", "mp3", "alac"));
        return map;
    }

    @GetMapping("/log")
    public Map<String, Object> log() {
        List<Map<String, String>> lines = new ArrayList<>();
        for (Log.Line line : Log.lines()) {
            lines.add(Map.of("time", line.time(), "level", line.level(),
                    "scope", line.scope(), "message", line.message()));
        }
        return Map.of("lines", lines);
    }
}
