package de.moritzf.airplaycapture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deviceName", deviceName);
        map.put("port", port);
        map.put("interfaceAddress", interfaceAddress);
        map.put("advertising", advertising);
        map.put("advertisedAs", advertisedAs);
        map.put("client", client);
        map.put("clientName", clientName);
        map.put("senderApp", senderApp);
        map.put("receiving", receiving);
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("title", nowTitle);
        now.put("artist", nowArtist);
        now.put("album", nowAlbum);
        now.put("startedAt", nowStartedAtMs);
        now.put("recordedMs", nowRecordedMs);
        now.put("durationMs", nowDurationMs);
        map.put("now", now);
        map.put("coverVersion", coverVersion);
        map.put("libraryDir", libraryDir);
        map.put("ffmpegAvailable", ffmpegAvailable);
        map.put("lastError", lastError);
        return map;
    }
}
