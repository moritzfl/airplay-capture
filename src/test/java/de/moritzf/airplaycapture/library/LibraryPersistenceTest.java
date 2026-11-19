package de.moritzf.airplaycapture.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryPersistenceTest {

    @TempDir
    Path data;

    @Test
    void namesSkipUnindexedFilesInAnyOutputFormat() throws IOException {
        Library library = new Library(new AppDirs(data), new Settings());
        Files.createFile(library.libraryDir().resolve("Recording.alac.m4a"));
        Files.createFile(library.libraryDir().resolve("Recording (2).mp3"));
        assertEquals("Recording (3)", library.uniqueBaseName("Recording"));
    }

    @Test
    void retainsIndexWhenMediaIsMissing() {
        AppDirs dirs = new AppDirs(data);
        Settings settings = new Settings();
        Library library = new Library(dirs, settings);
        LibraryTrack track = new LibraryTrack();
        track.id = "0123456789abcdef";
        track.files.put("mp3", "not-mounted.mp3");
        library.add(track);

        Library reopened = new Library(dirs, settings);
        assertEquals("not-mounted.mp3", reopened.find(track.id).orElseThrow().files.get("mp3"));
    }

    @Test
    void refusesOutOfLibraryMediaAndCoverNamesBeforeLoadingTheIndex() throws IOException {
        AppDirs dirs = new AppDirs(data);
        for (String entry : java.util.List.of(
            "{\"id\":\"0123456789abcdef\",\"files\":{\"mp3\":\"../other.mp3\"}}",
            "{\"id\":\"../outside\",\"files\":{}}"
        )) {
            Files.writeString(dirs.libraryFile(), "[" + entry + "]");
            assertThrows(IllegalStateException.class, () -> new Library(dirs, new Settings()));
        }
    }

    @Test
    void leavesInvalidIndexUntouched() throws IOException {
        AppDirs dirs = new AppDirs(data);
        Files.writeString(dirs.libraryFile(), "[{");
        assertThrows(IllegalStateException.class, () -> new Library(dirs, new Settings()));
        assertEquals("[{", Files.readString(dirs.libraryFile()));
    }
}
