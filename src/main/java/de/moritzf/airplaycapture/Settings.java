package de.moritzf.airplaycapture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** User-editable settings, persisted as JSON in the data directory. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Settings {

    /** Name shown in the AirPlay menu. */
    public String deviceName = "AirPlay Capture";

    /** RTSP port; 0 picks the first free port in {@link #PORT_RANGE_START}..{@link #PORT_RANGE_END}. */
    public int port = 0;

    /** Optional IPv4 address of the interface to advertise on; empty = auto. */
    public String interfaceAddress = "";

    /** Library folder, relative to the data directory unless absolute. */
    public String libraryDir = "library";

    /** Output formats: m4a (AAC), mp3, alac (lossless m4a). */
    public List<String> formats = new ArrayList<>(List.of("m4a", "mp3"));

    public int aacBitrateKbps = 192;

    /** LAME -q:a value, 0 (best) .. 9 (fastest). */
    public int mp3Quality = 2;

    /** MP3 rate control: {@code vbr} (quality-based) or {@code cbr} (fixed bitrate). */
    public String mp3Mode = "vbr";

    /** MP3 constant bitrate in kbps when {@link #mp3Mode} is {@code cbr}. */
    public int mp3BitrateKbps = 192;

    /** Recordings shorter than this are discarded ("accidental taps"). */
    public int minTrackSeconds = 3;

    /** Finalize the current track after this many seconds without audio. */
    public int idleFinalizeSeconds = 6;

    /**
     * How long to keep requesting missing RTP packets before skipping ahead.
     * AirPlay senders only keep a short retransmit window, so a longer wait recovers more gaps
     * but also delays the moment a damaged region is declared lost.
     */
    public int packetRecoveryMs = 2500;

    /** Keep the intermediate ALAC .caf file after exporting. */
    public boolean keepRaw = false;

    public String ffmpegPath = "ffmpeg";

    /** Fill missing tags (album, genre, year, track number, cover) from Apple's catalog. */
    public boolean metadataEnrichmentEnabled = true;

    /** Also use Deezer when Apple has no match. */
    public boolean metadataEnrichmentDeezer = true;

    // --- audiobooks ---
    /** Group consecutive chapters into one .m4b per book. */
    public boolean audiobookEnabled = true;

    /** Assemble detected audiobooks automatically when a book ends. Off: the user confirms. */
    public boolean audiobookAutoAssemble = false;

    /** Comma separated genre keywords that mark a track as an audiobook chapter. */
    public String audiobookGenres = "audiobook,audiobooks,hörbuch,hoerbuch";

    /** Start a book on a track at least this long (0 = only rely on genre/media kind). */
    public int audiobookLongTrackSeconds = 900;

    /** Assemble the book after this many seconds without a new chapter. */
    public int audiobookIdleSeconds = 300;

    /** Keep the individual chapter files after the .m4b was created. */
    public boolean audiobookKeepChapters = true;

    /** Split a long single-file audiobook at long pauses when assembling the .m4b. */
    public boolean audiobookSplitLongTracks = true;

    /** Use chapter names and durations from Deezer/MusicBrainz when a matching edition exists. */
    public boolean audiobookCatalogChapters = true;

    /** AAC bitrate for the .m4b file. */
    public int audiobookBitrateKbps = 96;

    /** Confirm audiobook candidates against Apple's public iTunes Search catalog. */
    public boolean audiobookLookupEnabled = true;

    /** ISO country code for the Apple Books catalog lookup. */
    public String audiobookLookupCountry = "US";

    /** Also check MusicBrainz release-group types (Audiobook/Spokenword/Audio drama). */
    public boolean audiobookLookupMusicBrainz = true;

    /** Optional contact for the MusicBrainz User-Agent (their API policy asks for one). */
    public String audiobookLookupContact = "";

    public static final int PORT_RANGE_START = 5000;
    public static final int PORT_RANGE_END = 6000;

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public static Settings load(Path file) {
        if (!Files.exists(file)) {
            return new Settings();
        }
        try {
            Settings settings = JSON.readValue(Files.readString(file), Settings.class);
            return settings == null ? new Settings() : settings.normalized();
        } catch (Exception e) {
            Log.warn("settings", "cannot read " + file + ": " + e.getMessage() + " - using defaults");
            return new Settings();
        }
    }

    public void save(Path file) throws IOException {
        Files.writeString(file, JSON.writeValueAsString(this));
    }

    public Settings copy() {
        Settings copy = new Settings();
        copy.copyFrom(this);
        return copy.normalized();
    }

    /** Updates this instance in place so every component keeps seeing the same settings bean. */
    public void copyFrom(Settings other) {
        deviceName = other.deviceName;
        port = other.port;
        interfaceAddress = other.interfaceAddress;
        libraryDir = other.libraryDir;
        formats = other.formats == null ? new ArrayList<>() : new ArrayList<>(other.formats);
        aacBitrateKbps = other.aacBitrateKbps;
        mp3Quality = other.mp3Quality;
        mp3Mode = other.mp3Mode;
        mp3BitrateKbps = other.mp3BitrateKbps;
        minTrackSeconds = other.minTrackSeconds;
        idleFinalizeSeconds = other.idleFinalizeSeconds;
        packetRecoveryMs = other.packetRecoveryMs;
        keepRaw = other.keepRaw;
        ffmpegPath = other.ffmpegPath;
        metadataEnrichmentEnabled = other.metadataEnrichmentEnabled;
        metadataEnrichmentDeezer = other.metadataEnrichmentDeezer;
        audiobookEnabled = other.audiobookEnabled;
        audiobookAutoAssemble = other.audiobookAutoAssemble;
        audiobookGenres = other.audiobookGenres;
        audiobookLongTrackSeconds = other.audiobookLongTrackSeconds;
        audiobookIdleSeconds = other.audiobookIdleSeconds;
        audiobookKeepChapters = other.audiobookKeepChapters;
        audiobookSplitLongTracks = other.audiobookSplitLongTracks;
        audiobookCatalogChapters = other.audiobookCatalogChapters;
        audiobookBitrateKbps = other.audiobookBitrateKbps;
        audiobookLookupEnabled = other.audiobookLookupEnabled;
        audiobookLookupCountry = other.audiobookLookupCountry;
        audiobookLookupMusicBrainz = other.audiobookLookupMusicBrainz;
        audiobookLookupContact = other.audiobookLookupContact;
    }

    public Settings normalized() {
        if (deviceName == null || deviceName.isBlank()) {
            deviceName = "AirPlay Capture";
        }
        deviceName = deviceName.trim();
        if (libraryDir == null || libraryDir.isBlank()) {
            libraryDir = "library";
        }
        if (formats == null) {
            formats = new ArrayList<>();
        }
        formats.removeIf(f -> f == null || !(f.equals("m4a") || f.equals("mp3") || f.equals("alac")));
        formats = new ArrayList<>(new LinkedHashSet<>(formats));
        aacBitrateKbps = clamp(aacBitrateKbps, 64, 320);
        mp3Quality = clamp(mp3Quality, 0, 9);
        if (mp3Mode == null || !(mp3Mode.equals("vbr") || mp3Mode.equals("cbr"))) {
            mp3Mode = "vbr";
        }
        mp3BitrateKbps = clamp(mp3BitrateKbps, 64, 320);
        minTrackSeconds = clamp(minTrackSeconds, 0, 600);
        idleFinalizeSeconds = clamp(idleFinalizeSeconds, 1, 120);
        packetRecoveryMs = clamp(packetRecoveryMs, 500, 10000);
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            ffmpegPath = "ffmpeg";
        }
        if (audiobookGenres == null) {
            audiobookGenres = "audiobook,audiobooks,hörbuch,hoerbuch";
        }
        audiobookGenres = audiobookGenres.trim();
        audiobookLongTrackSeconds = clamp(audiobookLongTrackSeconds, 0, 7200);
        audiobookIdleSeconds = clamp(audiobookIdleSeconds, 30, 7200);
        audiobookBitrateKbps = clamp(audiobookBitrateKbps, 32, 192);
        if (audiobookLookupCountry == null || audiobookLookupCountry.isBlank()) {
            audiobookLookupCountry = "US";
        }
        audiobookLookupCountry = audiobookLookupCountry.trim().toUpperCase(java.util.Locale.ROOT);
        if (audiobookLookupCountry.length() != 2) {
            audiobookLookupCountry = "US";
        }
        if (audiobookLookupContact == null) {
            audiobookLookupContact = "";
        }
        audiobookLookupContact = audiobookLookupContact.trim();
        if (port < 0 || port > 65535) {
            port = 0;
        }
        if (interfaceAddress == null) {
            interfaceAddress = "";
        }
        return this;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
