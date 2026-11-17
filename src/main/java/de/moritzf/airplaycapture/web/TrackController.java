package de.moritzf.airplaycapture.web;

import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves playback, artwork, previews and downloads to the web UI. */
@RestController
@RequestMapping("/api")
public class TrackController {

    private final Library library;
    private final AudiobookCreator audiobookCreator;

    public TrackController(Library library, AudiobookCreator audiobookCreator) {
        this.library = library;
        this.audiobookCreator = audiobookCreator;
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
            .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
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
    public ResponseEntity<Resource> file(
        @PathVariable String id,
        @PathVariable String format,
        @RequestParam(value = "download", required = false) String download
    ) {
        Optional<LibraryTrack> track = library.find(id);
        if (track.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = library.file(track.get(), format);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
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
        return media(path, contentType, path.getFileName().toString(), download != null);
    }

    @GetMapping("/audiobook-preview/{previewId}/{index}")
    public ResponseEntity<Resource> audiobookPreview(
        @PathVariable String previewId,
        @PathVariable int index
    ) throws IOException {
        Path clip;
        try {
            clip = audiobookCreator.previewClip(previewId, index);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().build();
        }
        if (clip == null || !Files.isRegularFile(clip)) {
            return ResponseEntity.notFound().build();
        }
        return media(clip, "audio/mp4", "chapter-preview.m4a", false);
    }

    @GetMapping("/audiobook-preview/{previewId}/{index}/full")
    public ResponseEntity<Resource> audiobookFullChapter(
        @PathVariable String previewId,
        @PathVariable int index
    ) throws IOException {
        Path file;
        try {
            file = audiobookCreator.fullChapter(previewId, index);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().build();
        }
        if (file == null || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String contentType = name.endsWith(".mp3")
            ? "audio/mpeg"
            : name.endsWith(".caf")
              ? "audio/x-caf"
              : "audio/mp4";
        return media(file, contentType, file.getFileName().toString(), false);
    }

    private static ResponseEntity<Resource> media(
        Path path,
        String contentType,
        String fileName,
        boolean attachment
    ) {
        ContentDisposition disposition = (
            attachment ? ContentDisposition.attachment() : ContentDisposition.inline()
        )
            .filename(fileName, StandardCharsets.UTF_8)
            .build();
        // Spring MVC handles range negotiation for seekable resources.
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(new FileSystemResource(path));
    }

    @GetMapping("/groups/{key}/zip/{format}")
    public void groupZip(
        @PathVariable String key,
        @PathVariable String format,
        @RequestParam(value = "all", required = false) String all,
        HttpServletResponse response
    ) throws IOException {
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
        response.setHeader(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString()
        );

        Set<String> used = new HashSet<>();
        try (
            ZipOutputStream zip = new ZipOutputStream(
                response.getOutputStream(),
                StandardCharsets.UTF_8
            )
        ) {
            for (int i = 0; i < files.size(); i++) {
                String name = names.get(i);
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                String suffix = dot > 0 ? name.substring(dot) : "";
                String entry = name;
                int counter = 2;
                while (!used.add(entry)) {
                    entry = stem + " (" + counter + ")" + suffix;
                    counter++;
                }
                zip.putNextEntry(new ZipEntry(entry));
                Files.copy(files.get(i), zip);
                zip.closeEntry();
            }
        }
    }

    private static String zipName(String title, String format) {
        String clean =
            title == null
                ? ""
                : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        if (clean.length() > 100) {
            clean = clean.substring(0, 100).trim();
        }
        if (clean.isEmpty()) {
            clean = "recordings";
        }
        return clean + " - " + format.toUpperCase(Locale.ROOT);
    }
}
