package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    @TempDir
    Path data;

    @Test
    void loadsDefaultsOnlyForMissingSettings() throws IOException {
        Path file = data.resolve("settings.json");
        assertEquals("AirPlay Capture", Settings.load(file).deviceName);
        Files.writeString(file, "{");
        assertThrows(IOException.class, () -> Settings.load(file));
        assertEquals("{", Files.readString(file));
    }

    @Test
    void replacesSettingsAndLeavesNoTemporaryFile() throws IOException {
        Path file = data.resolve("settings.json");
        Settings settings = new Settings();
        settings.save(file);
        settings.deviceName = "Updated";
        settings.formats = List.of("mp3", "mp3", "unknown");
        settings.normalized().save(file);

        assertEquals("Updated", Settings.load(file).deviceName);
        assertEquals(List.of("mp3"), Settings.load(file).formats);
        try (var files = Files.list(data)) {
            assertEquals(List.of(file), files.toList());
        }
    }
}
