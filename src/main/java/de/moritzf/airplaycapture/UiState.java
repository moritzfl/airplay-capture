package de.moritzf.airplaycapture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Live state shown in the web UI; mutated by the receiver and recording pipeline. */
public final class UiState {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public volatile String deviceName = "";
    public volatile int port = 0;
    public volatile String interfaceAddress = "";
    public volatile boolean advertising = false;
    public volatile String advertisedAs = "";
    public volatile String client = "";
    public volatile String clientName = "";
    public volatile String senderApp = "";
    public volatile boolean receiving = false;

    public volatile String nowTitle = "";
    public volatile String nowArtist = "";
    public volatile String nowAlbum = "";
    public volatile long nowStartedAtMs = 0;
    public volatile long nowRecordedMs = 0;
    public volatile long nowDurationMs = 0;
    public volatile long coverVersion = 0;

    public volatile String libraryDir = "";
    public volatile boolean ffmpegAvailable = false;
    public volatile String lastError = "";

    public void changed() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
