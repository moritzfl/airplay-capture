package de.moritzf.airplaycapture;

import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.media.Transcoder;
import de.moritzf.airplaycapture.raop.RaopServer;
import de.moritzf.airplaycapture.recording.RecordingManager;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Starts the RAOP receiver when the web application is ready and finalizes recordings on shutdown. */
@Component
public class AppLifecycle {

    private final RaopServer raopServer;
    private final RecordingManager recorder;
    private final Transcoder transcoder;
    private final UiState ui;
    private final Library library;
    private final AppDirs dirs;

    private volatile int webPort = 8080;

    public AppLifecycle(
        RaopServer raopServer,
        RecordingManager recorder,
        Transcoder transcoder,
        UiState ui,
        Library library,
        AppDirs dirs
    ) {
        this.raopServer = raopServer;
        this.recorder = recorder;
        this.transcoder = transcoder;
        this.ui = ui;
        this.library = library;
        this.dirs = dirs;
    }

    @EventListener(WebServerInitializedEvent.class)
    public void webServerStarted(WebServerInitializedEvent event) {
        this.webPort = event.getWebServer().getPort();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            raopServer.start();
        } catch (Exception e) {
            ui.lastError = "receiver failed to start: " + e.getMessage();
            Log.error("app", "cannot start receiver", e);
        }
        ui.ffmpegAvailable = transcoder.available();
        ui.libraryDir = library.libraryDir().toString();
        writePortFile();
        ui.changed();
        if (ui.advertising) {
            Log.info(
                "app",
                "ready - open http://localhost:" +
                    webPort +
                    "/app/ and pick '" +
                    ui.deviceName +
                    "' in the AirPlay menu"
            );
        } else {
            Log.warn(
                "app",
                "web interface ready on http://localhost:" +
                    webPort +
                    "/app/" +
                    ", but the AirPlay receiver is not advertised (see the mDNS error above)"
            );
        }
    }

    /** Makes the actual port discoverable when it differs from the configured one. */
    private void writePortFile() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "localhost";
        }
        String content =
            "Port: " +
            webPort +
            System.lineSeparator() +
            "http://" +
            host +
            ":" +
            webPort +
            "/app/" +
            System.lineSeparator();
        try {
            Files.writeString(dirs.dataDir().resolve("web-port.txt"), content);
        } catch (IOException e) {
            Log.warn("app", "cannot write web-port.txt: " + e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        Log.info("app", "shutting down");
        try {
            raopServer.stop();
        } catch (Exception e) {
            Log.warn("app", "receiver shutdown: " + e.getMessage());
        }
        recorder.shutdown();
    }
}
