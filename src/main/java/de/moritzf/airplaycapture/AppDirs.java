package de.moritzf.airplaycapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves and creates the writable application data directory. */
public final class AppDirs {

    private final Path dataDir;

    public AppDirs(Path dataDir) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    public static AppDirs resolve(String override) {
        String value = override;
        if (value == null || value.isBlank()) {
            value = System.getenv("AIRPLAY_CAPTURE_DATA");
        }
        if (value == null || value.isBlank()) {
            String home = System.getProperty("user.home", ".");
            boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            value = mac
                    ? home + "/Library/Application Support/AirPlayCapture"
                    : home + "/.airplay-capture";
        }
        AppDirs dirs = new AppDirs(Path.of(value));
        dirs.createDirectories();
        return dirs;
    }

    public void createDirectories() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(tmpDir());
            Files.createDirectories(metaDir());
            Files.createDirectories(coverDir());
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data directory " + dataDir, e);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path configFile() {
        return dataDir.resolve("settings.json");
    }

    public Path libraryFile() {
        return dataDir.resolve("library.json");
    }

    public Path tmpDir() {
        return dataDir.resolve("tmp");
    }

    public Path metaDir() {
        return dataDir.resolve("meta");
    }

    public Path coverDir() {
        return dataDir.resolve("covers");
    }
}
