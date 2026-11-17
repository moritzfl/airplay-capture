package de.moritzf.airplaycapture.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.moritzf.airplaycapture.AppDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AirplayConfigTest {

    @Test
    void keepsRawRecordingsOnStartup(@TempDir Path data) throws Exception {
        AppDirs dirs = new AppDirs(data);
        dirs.createDirectories();
        Path raw = Files.writeString(dirs.tmpDir().resolve("recording.caf"), "raw");
        Path work = Files.createDirectory(dirs.tmpDir().resolve("book-preview-123"));
        Files.writeString(work.resolve("chapter.caf"), "preview");

        AirplayConfig.cleanTempFiles(dirs);

        assertEquals("raw", Files.readString(raw));
        assertFalse(Files.exists(work));
    }
}
