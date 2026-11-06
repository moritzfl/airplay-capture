package de.moritzf.airplaycapture.config;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.media.Transcoder;
import de.moritzf.airplaycapture.raop.RaopServer;
import de.moritzf.airplaycapture.raop.ReceiverId;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import de.moritzf.airplaycapture.recording.RecordingManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free receiver and recording core as Spring beans. */
@Configuration
public class AirplayConfig {

    @Bean
    public AppDirs appDirs(@Value("${airplay-capture.data:}") String data) {
        AppDirs dirs = AppDirs.resolve(data);
        Log.info("app", "data directory: " + dirs.dataDir());
        cleanTempFiles(dirs);
        return dirs;
    }

    @Bean
    public Settings settings(AppDirs dirs) throws IOException {
        Settings settings = Settings.load(dirs.configFile()).normalized();
        String envLibraryDir = System.getenv("AIRPLAY_CAPTURE_LIBRARY_DIR");
        if (
            envLibraryDir != null &&
            !envLibraryDir.isBlank() &&
            !envLibraryDir.trim().equals(settings.libraryDir)
        ) {
            // The container contract owns the library/output folder. Outside Docker the variable
            // is absent, so the value from settings.json is used.
            settings.libraryDir = envLibraryDir.trim();
        }
        settings.save(dirs.configFile());
        return settings;
    }

    @Bean
    public UiState uiState() {
        return new UiState();
    }

    @Bean
    public Library library(AppDirs dirs, Settings settings) {
        return new Library(dirs, settings);
    }

    @Bean
    public Transcoder transcoder(Settings settings) {
        return new Transcoder(settings);
    }

    @Bean
    public RecordingManager recordingManager(
        AppDirs dirs,
        Library library,
        Transcoder transcoder,
        UiState uiState,
        Settings settings
    ) {
        return new RecordingManager(dirs, library, transcoder, uiState, settings);
    }

    @Bean
    public AudiobookCreator audiobookCreator(
        AppDirs dirs,
        Library library,
        UiState uiState,
        Settings settings
    ) {
        return new AudiobookCreator(dirs, library, uiState, settings);
    }

    @Bean
    public AudiobookJobs audiobookJobs(AppDirs dirs, UiState uiState) {
        return new AudiobookJobs(dirs, uiState);
    }

    @Bean
    public RaopServer raopServer(
        AppDirs dirs,
        Settings settings,
        RecordingManager recorder,
        UiState uiState
    ) {
        byte[] deviceId = ReceiverId.load(dirs.dataDir().resolve("receiver-id"));
        return new RaopServer(settings, recorder, uiState, deviceId);
    }

    private static void cleanTempFiles(AppDirs dirs) {
        Path tmp = dirs.tmpDir();
        if (!Files.isDirectory(tmp)) {
            return;
        }
        try (Stream<Path> files = Files.walk(tmp)) {
            files
                .sorted(Comparator.reverseOrder())
                .filter(path -> !path.equals(tmp))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
        } catch (IOException e) {
            Log.warn("app", "cannot clean temp folder: " + e.getMessage());
        }
    }
}
