package de.moritzf.airplaycapture.config;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.media.Transcoder;
import de.moritzf.airplaycapture.raop.RaopServer;
import de.moritzf.airplaycapture.recording.RecordingManager;
import java.io.IOException;
import org.springframework.stereotype.Service;

/** Applies settings changes to every component and restarts the receiver when its identity changes. */
@Service
public class SettingsService {

    private final AppDirs dirs;
    private final Settings settings;
    private final Library library;
    private final Transcoder transcoder;
    private final RecordingManager recorder;
    private final RaopServer raopServer;
    private final UiState ui;

    public SettingsService(
        AppDirs dirs,
        Settings settings,
        Library library,
        Transcoder transcoder,
        RecordingManager recorder,
        RaopServer raopServer,
        UiState ui
    ) {
        this.dirs = dirs;
        this.settings = settings;
        this.library = library;
        this.transcoder = transcoder;
        this.recorder = recorder;
        this.raopServer = raopServer;
        this.ui = ui;
    }

    public Settings current() {
        return settings;
    }

    public synchronized Settings apply(Settings incoming) throws IOException {
        Settings next = incoming.copy();
        boolean receiverChanged =
            !settings.deviceName.equals(next.deviceName) ||
            settings.port != next.port ||
            !settings.interfaceAddress.equals(next.interfaceAddress);
        boolean libraryChanged = !settings.libraryDir.equals(next.libraryDir);

        next.save(dirs.configFile());
        settings.copyFrom(next);

        library.updateSettings(settings);
        transcoder.updateSettings(settings);
        recorder.updateSettings(settings);
        raopServer.updateSettings(settings);

        ui.deviceName = settings.deviceName;
        ui.libraryDir = library.libraryDir().toString();
        ui.ffmpegAvailable = transcoder.available();
        ui.changed();
        Log.info("app", "settings saved");

        if (receiverChanged) {
            Log.info("app", "restarting receiver for new name/port/interface");
            try {
                raopServer.restart();
            } catch (IOException e) {
                Log.error("app", "cannot restart receiver: " + e.getMessage());
                ui.lastError = "receiver restart failed: " + e.getMessage();
                throw e;
            }
        }
        if (libraryChanged) {
            Log.info("library", "library folder is now " + library.libraryDir());
        }
        return settings;
    }
}
