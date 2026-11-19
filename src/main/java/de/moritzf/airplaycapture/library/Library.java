package de.moritzf.airplaycapture.library;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Persistent list of exported tracks plus their files and cover images. */
public final class Library {

    private static final List<String> MEDIA_SUFFIXES = List.of(
        ".m4a",
        ".alac.m4a",
        ".mp3",
        ".caf",
        ".m4b"
    );

    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final AppDirs dirs;
    private final List<LibraryTrack> tracks = new ArrayList<>();
    private volatile Settings settings;

    public Library(AppDirs dirs, Settings settings) {
        this.dirs = dirs;
        this.settings = settings;
        load();
    }

    public synchronized void updateSettings(Settings settings) {
        this.settings = settings;
        applyNewLibraryDir();
    }

    public Path libraryDir() {
        Path dir = Path.of(settings.libraryDir);
        return dir.isAbsolute() ? dir : dirs.dataDir().resolve(dir);
    }

    private void applyNewLibraryDir() {
        try {
            Files.createDirectories(libraryDir());
        } catch (IOException e) {
            Log.error("library", "cannot create " + libraryDir() + ": " + e.getMessage());
        }
    }

    public Path coverFile(String id, boolean png) {
        return child(dirs.coverDir(), id + (png ? ".png" : ".jpg"));
    }

    private static Path child(Path dir, String name) {
        if (
            name == null ||
            name.isBlank() ||
            name.length() > 255 ||
            name.equals(".") ||
            name.equals("..") ||
            name.indexOf('/') >= 0 ||
            name.indexOf('\\') >= 0 ||
            name.indexOf(':') >= 0 ||
            name.indexOf('\0') >= 0
        ) {
            throw new IllegalArgumentException("Invalid library filename");
        }
        Path path = dir.normalize().resolve(name).normalize();
        if (
            !path.startsWith(dir.normalize()) || Files.isSymbolicLink(path)
        ) throw new IllegalArgumentException("Unsafe library path");
        return path;
    }

    public synchronized List<LibraryTrack> tracks() {
        List<LibraryTrack> copy = new ArrayList<>(tracks);
        copy.sort(Comparator.comparingLong((LibraryTrack t) -> t.recordedAtEpochMs).reversed());
        return copy;
    }

    public synchronized Optional<LibraryTrack> find(String id) {
        return tracks
            .stream()
            .filter(t -> t.id.equals(id))
            .findFirst();
    }

    public synchronized Path file(LibraryTrack track, String format) {
        String name = track.files.get(format);
        if (name == null) {
            return null;
        }
        return child(libraryDir(), name);
    }

    /** Size of one exported format; 0 when the file is missing. */
    public synchronized long fileSize(LibraryTrack track, String format) {
        Path path = file(track, format);
        if (path == null) {
            return 0;
        }
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    public synchronized void add(LibraryTrack track) {
        tracks.add(track);
        save();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dirs.dataDir());
            AtomicFiles.writeString(dirs.libraryFile(), JSON.writeValueAsString(tracks));
        } catch (IOException e) {
            Log.error("library", "cannot save library: " + e.getMessage());
        }
    }

    public synchronized void delete(LibraryTrack track) {
        tracks.removeIf(t -> t.id.equals(track.id));
        for (String name : track.files.values()) {
            deleteQuietly(child(libraryDir(), name));
        }
        deleteQuietly(coverFile(track.id, true));
        deleteQuietly(coverFile(track.id, false));
        save();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Log.warn("library", "cannot delete " + path + ": " + e.getMessage());
        }
    }

    /** Avoids clobbering an existing recording with the same artist/title. */
    public synchronized String uniqueBaseName(String desired) {
        String base = desired;
        int counter = 2;
        while (conflicts(base)) {
            base = desired + " (" + counter + ")";
            counter++;
        }
        return base;
    }

    private boolean conflicts(String base) {
        Path dir = libraryDir();
        for (String suffix : MEDIA_SUFFIXES) {
            if (Files.exists(dir.resolve(base + suffix))) {
                return true;
            }
        }
        for (LibraryTrack track : tracks) {
            if (base.equals(track.baseName)) {
                return true;
            }
        }
        return false;
    }

    private String existingCoverHash(String id) {
        Path jpeg = coverFile(id, false);
        if (Files.exists(jpeg)) {
            return CoverHash.of(jpeg);
        }
        Path png = coverFile(id, true);
        return Files.exists(png) ? CoverHash.of(png) : "";
    }

    private void load() {
        applyNewLibraryDir();
        try {
            String json = Files.readString(dirs.libraryFile(), StandardCharsets.UTF_8);
            List<LibraryTrack> loaded = JSON.readValue(
                json,
                new TypeReference<List<LibraryTrack>>() {}
            );
            if (loaded == null) {
                throw new IOException("expected a JSON array");
            }
            for (LibraryTrack track : loaded) {
                if (
                    track == null ||
                    track.id == null ||
                    !track.id.matches("[0-9a-f]{16}") ||
                    track.files == null
                ) {
                    throw new IOException("invalid track entry");
                }
                for (String name : track.files.values()) child(libraryDir(), name);
                // Retain indexed files even if the library mount is temporarily unavailable.
                if (track.coverHash == null || track.coverHash.isBlank()) {
                    track.coverHash = existingCoverHash(track.id);
                }
                if ("processing".equals(track.status)) {
                    track.status = "error";
                    track.error = "Export interrupted; check the tmp folder for the raw CAF.";
                }
            }
            tracks.addAll(loaded);
        } catch (NoSuchFileException ignored) {
            // New library.
        } catch (Exception e) {
            throw new IllegalStateException("cannot read library from " + dirs.libraryFile(), e);
        }
    }
}
