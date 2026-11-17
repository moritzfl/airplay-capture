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
    void retainsIndexWhenMediaIsMissing() {
        AppDirs dirs = new AppDirs(data);
        Settings settings = new Settings();
        Library library = new Library(dirs, settings);
        LibraryTrack track = new LibraryTrack();
        track.id = "test";
        track.files.put("mp3", "not-mounted.mp3");
        library.add(track);

        Library reopened = new Library(dirs, settings);
        assertEquals("not-mounted.mp3", reopened.find("test").orElseThrow().files.get("mp3"));
    }

    @Test
    void leavesInvalidIndexUntouched() throws IOException {
        AppDirs dirs = new AppDirs(data);
        Files.writeString(dirs.libraryFile(), "[{");
        assertThrows(IllegalStateException.class, () -> new Library(dirs, new Settings()));
        assertEquals("[{", Files.readString(dirs.libraryFile()));
    }
}
