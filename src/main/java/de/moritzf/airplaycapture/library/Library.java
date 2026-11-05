package de.moritzf.airplaycapture.library;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Persistent list of exported tracks plus their files and cover images. */
public final class Library {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final AppDirs dirs;
    private final List<LibraryTrack> tracks = new ArrayList<>();
    private Settings settings;

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
        return dirs.coverDir().resolve(id + (png ? ".png" : ".jpg"));
    }

    public synchronized List<LibraryTrack> tracks() {
        List<LibraryTrack> copy = new ArrayList<>(tracks);
        copy.sort(Comparator.comparingLong((LibraryTrack t) -> t.recordedAtEpochMs).reversed());
        return copy;
    }

    public synchronized Optional<LibraryTrack> find(String id) {
        return tracks.stream().filter(t -> t.id.equals(id)).findFirst();
    }

    public synchronized Path file(LibraryTrack track, String format) {
        String name = track.files.get(format);
        if (name == null) {
            return null;
        }
        return libraryDir().resolve(name);
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
            Files.writeString(dirs.libraryFile(), JSON.writeValueAsString(tracks), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("library", "cannot save library: " + e.getMessage());
        }
    }

    public synchronized void delete(LibraryTrack track) {
        tracks.removeIf(t -> t.id.equals(track.id));
        for (String name : track.files.values()) {
            deleteQuietly(libraryDir().resolve(name));
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
    public synchronized String uniqueBaseName(String desired, String extension) {
        String base = desired;
        int counter = 2;
        while (conflicts(base, extension)) {
            base = desired + " (" + counter + ")";
            counter++;
        }
        return base;
    }

    private boolean conflicts(String base, String extension) {
        Path dir = libraryDir();
        if (Files.exists(dir.resolve(base + extension))) {
            return true;
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
        if (!Files.exists(dirs.libraryFile())) {
            return;
        }
        try {
            String json = Files.readString(dirs.libraryFile(), StandardCharsets.UTF_8);
            List<LibraryTrack> loaded = JSON.readValue(json, new TypeReference<List<LibraryTrack>>() {
            });
            if (loaded != null) {
                boolean hashesChanged = false;
                for (LibraryTrack track : loaded) {
                    if (track.id == null) {
                        continue;
                    }
                    // Drop entries whose files vanished (manual cleanup).
                    track.files.entrySet().removeIf(e -> !Files.exists(libraryDir().resolve(e.getValue())));
                    if (!track.files.isEmpty()) {
                        if (track.coverHash == null || track.coverHash.isBlank()) {
                            String hash = existingCoverHash(track.id);
                            if (!hash.isBlank()) {
                                track.coverHash = hash;
                                hashesChanged = true;
                            }
                        }
                        tracks.add(track);
                    }
                }
                if (hashesChanged) {
                    save();
                }
            }
        } catch (Exception e) {
            Log.warn("library", "cannot read library: " + e.getMessage());
        }
    }
}
