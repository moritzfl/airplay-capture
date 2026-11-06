package de.moritzf.airplaycapture.recording;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.library.CoverHash;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.media.AlacConfig;
import de.moritzf.airplaycapture.media.AudiobookAssembler;
import de.moritzf.airplaycapture.media.AudiobookLookup;
import de.moritzf.airplaycapture.media.CafWriter;
import de.moritzf.airplaycapture.media.ChapterSplitter;
import de.moritzf.airplaycapture.media.MetadataEnricher;
import de.moritzf.airplaycapture.media.Transcoder;
import de.moritzf.airplaycapture.util.ClientNameResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Turns the RAOP stream into one file per song: metadata and artwork are correlated with the audio
 * stream, each song is captured as ALAC-in-CAF and exported by ffmpeg afterwards.
 */
public final class RecordingManager {

    /**
     * Live cover versions are part of the cover URL, so each must be new to the page: a browser
     * shows an image it already loaded for a URL again without asking, a failed load included.
     * Counting from the start time keeps them unique across tracks and restarts.
     */
    private static final AtomicLong COVER_VERSIONS = new AtomicLong(System.currentTimeMillis());

    private static final Pattern INVALID_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final AppDirs dirs;
    private final Library library;
    private final Transcoder transcoder;
    private final UiState ui;
    private final AudiobookLookup audiobookLookup = new AudiobookLookup();

    private final Object lock = new Object();
    private final ExecutorService exporter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "export");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread thread = new Thread(r, "recorder-tick");
            thread.setDaemon(true);
            return thread;
        }
    );

    private volatile Settings settings;
    private MetadataEnricher metadataEnricher;
    private Draft current;
    private Book activeBook;
    /** Non-zero while the RAOP session waits for retransmits; blocks the silence timeout. */
    private long gapPendingSince;
    private byte[] pendingCover;
    private String pendingCoverMime;
    private volatile String clientIp = "";

    public RecordingManager(
        AppDirs dirs,
        Library library,
        Transcoder transcoder,
        UiState ui,
        Settings settings
    ) {
        this.dirs = dirs;
        this.library = library;
        this.transcoder = transcoder;
        this.ui = ui;
        this.settings = settings;
        this.metadataEnricher = new MetadataEnricher(
            settings.audiobookLookupCountry,
            settings.metadataEnrichmentDeezer
        );
        ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void updateSettings(Settings settings) {
        this.settings = settings;
        this.metadataEnricher = new MetadataEnricher(
            settings.audiobookLookupCountry,
            settings.metadataEnrichmentDeezer
        );
    }

    public void onClientConnected(String address) {
        clientIp = address;
        ui.client = address;
        ui.clientName = "";
        ui.senderApp = "";
        ui.changed();
        Thread.ofVirtual()
            .name("client-name")
            .start(() -> {
                String name = ClientNameResolver.resolve(address);
                if (address.equals(clientIp)) {
                    ui.clientName = name;
                    ui.changed();
                }
            });
    }

    /** Called when the RTSP connection announced the stream (codec config). */
    public void onStreamConfigured(AlacConfig config) {
        this.lastConfig = config;
        Log.info(
            "recorder",
            "stream configured: " +
                config.sampleRate() +
                " Hz, " +
                config.channels() +
                " ch, " +
                config.bitDepth() +
                " bit, frame " +
                config.frameLength() +
                " samples"
        );
    }

    public void onMetadata(TrackMetadata track) {
        String title = clean(track.title());
        String artist = clean(track.artist());
        String album = clean(track.album());
        String genre = clean(track.genre());
        Integer trackNumber = track.trackNumber();
        Integer year = track.year();
        Integer mediaKind = track.mediaKind();

        synchronized (lock) {
            if (current == null) {
                current = newDraft();
            } else if (!current.matches(title, artist, album)) {
                Log.info("recorder", "new track: " + describe(artist, title));
                finalizeLocked("next track");
                current = newDraft();
            }
            if (!isBlank(title)) {
                current.title = title;
            }
            if (!isBlank(artist)) {
                current.artist = artist;
            }
            if (!isBlank(album)) {
                current.album = album;
            }
            if (!isBlank(genre)) {
                current.genre = genre;
            }
            if (trackNumber != null) {
                current.trackNumber = trackNumber;
            }
            if (year != null && year > 1000 && year < 3000) {
                current.year = year;
            }
            if (mediaKind != null) {
                current.mediaKind = mediaKind;
            }
            if (current.cover == null && pendingCover != null) {
                current.cover = pendingCover;
                current.coverMime = pendingCoverMime;
                pendingCover = null;
                pendingCoverMime = null;
            }
            if (current.writer == null) {
                openWriter(current);
            }
            publishNow(current);
            Log.info(
                "recorder",
                "metadata: " +
                    describe(current.artist, current.title) +
                    (isBlank(current.album) ? "" : " / " + current.album)
            );
            if (settings.audiobookEnabled) {
                if (!isBlank(current.album)) {
                    audiobookLookup.request(current.album, current.artist, lookupOptions());
                }
                if (isBlank(current.album) && !isBlank(current.title)) {
                    audiobookLookup.request(current.title, null, current.artist, lookupOptions());
                }
            }
        }
    }

    public void onArtwork(byte[] image, String contentType) {
        if (image == null || image.length == 0) {
            return;
        }
        synchronized (lock) {
            if (current == null) {
                pendingCover = image;
                pendingCoverMime = contentType;
            } else if (current.cover == null) {
                current.cover = image;
                current.coverMime = contentType;
            }
            // A repeated artwork block for the same song is ignored (not queued for the next track).
            writeLiveCover(image, contentType);
            ui.changed();
        }
        Log.info("recorder", "artwork: " + image.length + " bytes (" + contentType + ")");
    }

    public void onArtworkNone() {
        synchronized (lock) {
            pendingCover = null;
            pendingCoverMime = null;
        }
    }

    public void onProgress(long positionSeconds, long durationSeconds) {
        synchronized (lock) {
            if (current == null) {
                return;
            }
            if (durationSeconds > 0) {
                current.durationHintMs = durationSeconds * 1000;
            }
            ui.nowDurationMs = current.durationHintMs;
        }
    }

    public void onAudio(long rtpTime, byte[] data, int offset, int length, int frameCount) {
        synchronized (lock) {
            if (current == null) {
                current = newDraft();
            }
            if (current.writer == null && !openWriter(current)) {
                return;
            }
            if (current.startRtpTime < 0) {
                current.startRtpTime = rtpTime;
            }
            try {
                current.writer.writeFrame(data, offset, length);
            } catch (IOException e) {
                Log.error("recorder", "cannot write audio: " + e.getMessage());
                current.failed = true;
                return;
            }
            current.frames += frameCount;
            current.lastAudioAt = System.currentTimeMillis();
            if (!current.started) {
                current.started = true;
                current.startedAt = current.lastAudioAt;
                Log.info("recorder", "recording " + describe(current.artist, current.title));
            }
            ui.receiving = true;
            ui.nowRecordedMs = current.recordedMillis();
        }
    }

    /**
     * Records packets the sender never resent. With {@code fill}, the hole is written as ALAC
     * silence, so the recording keeps its length and later audio stays where it belongs.
     */
    public void noteGap(int missingPackets, boolean fill) {
        synchronized (lock) {
            if (current == null) {
                return;
            }
            current.gaps++;
            current.gapPackets += Math.max(1, missingPackets);
            if (!fill || current.writer == null || current.config == null) {
                return;
            }
            try {
                if (current.writer.writeSilence(missingPackets)) {
                    current.frames += (long) missingPackets * current.config.frameLength();
                    current.filledPackets += missingPackets;
                }
            } catch (IOException e) {
                Log.error("recorder", "cannot write silence: " + e.getMessage());
                current.failed = true;
            }
        }
    }

    /** Records a gap that retransmits (or late packets) closed in time. */
    public void noteRecovered(long waitedMs) {
        synchronized (lock) {
            if (current != null) {
                current.recoveredGaps++;
                current.maxRecoveryMs = Math.max(current.maxRecoveryMs, waitedMs);
            }
        }
    }

    /**
     * Marks whether the receiver is currently holding packets while it waits for retransmits.
     * The silence timeout must not end a track in the middle of that window, otherwise delayed
     * packets start a new file.
     */
    public void onGapPending(boolean pending) {
        synchronized (lock) {
            gapPendingSince = pending
                ? gapPendingSince == 0
                    ? System.currentTimeMillis()
                    : gapPendingSince
                : 0;
        }
    }

    public void onFlush() {
        synchronized (lock) {
            finalizeLocked("flush");
        }
    }

    public void onSessionEnded() {
        synchronized (lock) {
            if (current != null) {
                finalizeLocked("disconnect");
            }
            finalizeBookLocked("disconnect");
            clientIp = "";
            ui.client = "";
            ui.clientName = "";
            ui.receiving = false;
            publishNow(null);
            ui.changed();
        }
    }

    /** Finalizes the running recording and waits for pending exports. */
    public void shutdown() {
        synchronized (lock) {
            finalizeLocked("shutdown");
            finalizeBookLocked("shutdown");
        }
        ticker.shutdownNow();
        audiobookLookup.close();
        exporter.shutdown();
        try {
            if (!exporter.awaitTermination(180, TimeUnit.SECONDS)) {
                Log.warn("recorder", "export still running at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tick() {
        boolean changed = false;
        synchronized (lock) {
            if (current != null && current.started) {
                long idleMillis = settings.idleFinalizeSeconds * 1000L;
                if (
                    gapPendingSince == 0 &&
                    System.currentTimeMillis() - current.lastAudioAt > idleMillis
                ) {
                    Log.info(
                        "recorder",
                        "finalizing after " + settings.idleFinalizeSeconds + "s of silence"
                    );
                    finalizeLocked("silence");
                    changed = true;
                } else {
                    ui.nowRecordedMs = current.recordedMillis();
                }
            }
            if (
                activeBook != null &&
                current == null &&
                gapPendingSince == 0 &&
                System.currentTimeMillis() - activeBook.lastChapterAt >
                    settings.audiobookIdleSeconds * 1000L
            ) {
                Log.info(
                    "recorder",
                    "no new audiobook chapter for " +
                        settings.audiobookIdleSeconds +
                        "s, assembling the book"
                );
                finalizeBookLocked("idle");
                changed = true;
            }
        }
        if (changed) {
            ui.changed();
        }
    }

    private boolean openWriter(Draft draft) {
        try {
            draft.caf = dirs.tmpDir().resolve(draft.id + ".caf");
            draft.writer = new CafWriter(draft.caf, draft.config);
            return true;
        } catch (IOException e) {
            Log.error("recorder", "cannot create recording file: " + e.getMessage());
            draft.failed = true;
            return false;
        }
    }

    private Draft newDraft() {
        Draft draft = new Draft();
        draft.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        draft.config = lastConfig;
        return draft;
    }

    private volatile AlacConfig lastConfig = new AlacConfig(
        352,
        0,
        16,
        40,
        10,
        14,
        2,
        255,
        0,
        0,
        44100
    );

    private void finalizeLocked(String reason) {
        Draft draft = current;
        current = null;
        gapPendingSince = 0;
        if (draft == null) {
            return;
        }
        if (draft.writer != null) {
            try {
                draft.writer.close();
            } catch (IOException e) {
                Log.error("recorder", "cannot finish recording: " + e.getMessage());
                draft.failed = true;
            }
        }
        ui.receiving = false;
        publishNow(null);
        ui.changed();
        if (draft.frames == 0 || draft.failed) {
            deleteQuietly(draft.caf);
            Log.info("recorder", "discarded empty recording (" + reason + ")");
            return;
        }
        long durationMs = draft.recordedMillis();
        if (durationMs < settings.minTrackSeconds * 1000L) {
            deleteQuietly(draft.caf);
            Log.info(
                "recorder",
                "discarded " +
                    durationMs +
                    "ms recording (" +
                    reason +
                    ", shorter than " +
                    settings.minTrackSeconds +
                    "s)"
            );
            return;
        }
        LibraryTrack track = new LibraryTrack();
        track.id = draft.id;
        track.title = draft.title;
        track.artist = draft.artist;
        track.album = draft.album;
        track.genre = draft.genre;
        track.trackNumber = draft.trackNumber;
        track.year = draft.year;
        track.durationMs = durationMs;
        track.expectedDurationMs = draft.durationHintMs;
        track.incomplete = isIncomplete(durationMs, draft.durationHintMs);
        track.recordedAtEpochMs = draft.started ? draft.startedAt : System.currentTimeMillis();
        track.source = clientIp;
        track.gaps = draft.gaps;
        track.gapPackets = draft.gapPackets;
        track.filledPackets = draft.filledPackets;
        track.recoveredGaps = draft.recoveredGaps;
        track.maxRecoveryMs = draft.maxRecoveryMs;
        track.mediaKind = draft.mediaKind;
        track.status = "processing";
        track.baseName = library.uniqueBaseName(sanitize(suggestName(draft)), preferredExtension());
        if (track.incomplete) {
            Log.warn(
                "recorder",
                "incomplete recording: " +
                    formatDuration(durationMs) +
                    " of " +
                    formatDuration(track.expectedDurationMs) +
                    " captured (" +
                    reason +
                    ")"
            );
        }

        Path coverPath = null;
        if (draft.cover != null && draft.cover.length > 0) {
            try {
                boolean png =
                    draft.coverMime != null &&
                    draft.coverMime.toLowerCase(Locale.ROOT).contains("png");
                coverPath = library.coverFile(track.id, png);
                Files.createDirectories(coverPath.getParent());
                Files.write(coverPath, draft.cover);
                track.cover = true;
                track.coverHash = CoverHash.of(coverPath);
            } catch (IOException e) {
                Log.warn("recorder", "cannot store artwork: " + e.getMessage());
                coverPath = null;
            }
        }
        if (isAudiobookChapter(track)) {
            track.audiobookChapter = true;
            if (settings.audiobookAutoAssemble) {
                Book book = ensureBook(track);
                track.bookId = book.id;
                book.chapters.add(new BookChapter(track, draft.caf, coverPath));
                book.lastChapterAt = System.currentTimeMillis();
                Log.info(
                    "recorder",
                    "audiobook chapter " +
                        book.chapters.size() +
                        ": " +
                        describe(track.artist, track.title) +
                        " / " +
                        book.title
                );
            }
        } else if (activeBook != null) {
            finalizeBookLocked("next track");
        }
        library.add(track);
        Log.info(
            "recorder",
            "saved " +
                track.baseName +
                " (" +
                formatDuration(durationMs) +
                ", " +
                reason +
                (draft.gaps > 0
                    ? ", " + draft.gaps + " gaps / " + draft.gapPackets + " packets"
                    : "") +
                (draft.recoveredGaps > 0
                    ? ", " + draft.recoveredGaps + " recovered (max " + draft.maxRecoveryMs + " ms)"
                    : "") +
                ")"
        );
        ui.changed();

        Path caf = draft.caf;
        Path finalCover = coverPath;
        exporter.submit(() -> export(track, caf, finalCover));
    }

    private void export(LibraryTrack track, Path caf, Path cover) {
        try {
            Path effectiveCover = cover;
            if (needsEnrichment(track)) {
                effectiveCover = enrich(track, effectiveCover);
            }
            Transcoder.TrackTags tags = new Transcoder.TrackTags(
                track.title,
                track.artist,
                track.album,
                track.genre,
                track.trackNumber,
                track.year
            );
            Transcoder.Result result =
                settings.formats.isEmpty() || !transcoder.available()
                    ? new Transcoder.Result(new LinkedHashMap<>(), "no output format available")
                    : transcoder.export(
                          caf,
                          effectiveCover,
                          tags,
                          track.baseName,
                          library.libraryDir()
                      );

            Path libraryDir = library.libraryDir();
            Files.createDirectories(libraryDir);
            Map<String, String> files = new LinkedHashMap<>(result.files());
            boolean keepForAutoBook = track.audiobookChapter && settings.audiobookAutoAssemble;
            if (keepForAutoBook) {
                // Keep the CAF in the temp folder until the automatic book assembly.
            } else if (settings.keepRaw || files.isEmpty()) {
                Path target = libraryDir.resolve(track.baseName + ".caf");
                move(caf, target);
                files.put("caf", target.getFileName().toString());
            } else {
                deleteQuietly(caf);
            }
            long size = 0;
            for (String name : files.values()) {
                Path path = libraryDir.resolve(name);
                if (Files.exists(path)) {
                    size += Files.size(path);
                }
            }
            synchronized (library) {
                track.files = files;
                track.sizeBytes = size;
                track.status = files.isEmpty() ? "error" : "ready";
                track.error = result.error();
                library.save();
            }
            if (!files.isEmpty()) {
                Log.info("export", "ready: " + track.baseName + " (" + files.keySet() + ")");
            }
        } catch (Exception e) {
            Log.error("export", "failed for " + track.baseName, e);
            synchronized (library) {
                track.status = "error";
                track.error = e.getMessage();
                library.save();
            }
        } finally {
            if (!settings.keepRaw && !(track.audiobookChapter && settings.audiobookAutoAssemble)) {
                deleteQuietly(caf);
            }
            ui.changed();
        }
    }

    // ---------------------------------------------------------------- audiobooks

    private boolean isAudiobookChapter(LibraryTrack track) {
        if (!settings.audiobookEnabled || (isBlank(track.album) && isBlank(track.title))) {
            return false;
        }
        if (activeBook != null && activeBook.key.equals(bookKey(track))) {
            return true; // continuation of the running book, even if genre is missing
        }
        boolean appSignal = matchesAudiobookApp(ui.senderApp);
        boolean genreSignal = matchesAudiobookGenre(track.genre);
        boolean kindSignal = track.mediaKind != null && track.mediaKind == 8;
        boolean albumLookup =
            !isBlank(track.album) &&
            audiobookLookup
                .cached(track.title, track.album, track.artist, lookupOptions())
                .map(AudiobookLookup.Verdict::audiobook)
                .orElse(false);
        boolean titleLookup = audiobookLookup
            .cached(track.title, null, track.artist, lookupOptions())
            .map(AudiobookLookup.Verdict::audiobook)
            .orElse(false);
        // A title-only catalog match is only trusted for a long recording.
        long titleLookupMinMs =
            (settings.audiobookLongTrackSeconds > 0 ? settings.audiobookLongTrackSeconds : 900) *
            1000L;
        boolean lookupSignal = albumLookup || (titleLookup && track.durationMs >= titleLookupMinMs);
        boolean longSignal =
            !isBlank(track.album) &&
            settings.audiobookLongTrackSeconds > 0 &&
            track.durationMs >= settings.audiobookLongTrackSeconds * 1000L;
        if (genreSignal || kindSignal || lookupSignal || longSignal || appSignal) {
            String signal = genreSignal
                ? "genre"
                : kindSignal
                  ? "media kind"
                  : lookupSignal
                    ? "catalog lookup"
                    : longSignal
                      ? "length"
                      : "sender app";
            Log.info("recorder", "audiobook detected by " + signal);
            return true;
        }
        return false;
    }

    private AudiobookLookup.Options lookupOptions() {
        return new AudiobookLookup.Options(
            settings.audiobookLookupEnabled,
            settings.audiobookLookupMusicBrainz,
            settings.audiobookLookupCountry,
            settings.audiobookLookupContact
        );
    }

    private static boolean matchesAudiobookApp(String app) {
        if (isBlank(app)) {
            return false;
        }
        String value = app.toLowerCase(Locale.ROOT);
        for (String keyword : List.of(
            "audible",
            "lylli",
            "bookbeat",
            "storytel",
            "nextory",
            "kobo",
            "blinkist",
            "scribd",
            "everand",
            "books",
            "audiobookshelf"
        )) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAudiobookGenre(String genre) {
        if (isBlank(genre)) {
            return false;
        }
        String value = genre.toLowerCase(Locale.ROOT);
        for (String keyword : settings.audiobookGenres.split(",")) {
            String trimmed = keyword.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && value.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static String bookKey(LibraryTrack track) {
        String album = norm(track.album);
        return album.isBlank() ? norm(track.title) : album;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Book ensureBook(LibraryTrack track) {
        String key = bookKey(track);
        if (activeBook != null && !activeBook.key.equals(key)) {
            finalizeBookLocked("different book");
        }
        if (activeBook == null) {
            String title = isBlank(track.album) ? track.title : track.album;
            String author = isBlank(track.artist) ? "" : track.artist;
            activeBook = new Book(key, title, author);
            Log.info("recorder", "audiobook started: " + describe(author, title));
        }
        return activeBook;
    }

    private void finalizeBookLocked(String reason) {
        Book book = activeBook;
        activeBook = null;
        if (book == null || book.chapters.isEmpty()) {
            return;
        }
        Log.info(
            "recorder",
            "audiobook finished: " +
                describe(book.author, book.title) +
                " (" +
                book.chapters.size() +
                " chapters, " +
                reason +
                ")"
        );
        exporter.submit(() -> assembleBook(book));
    }

    private void assembleBook(Book book) {
        List<AudiobookAssembler.Chapter> chapters = new ArrayList<>();
        List<BookChapter> usable = new ArrayList<>();
        for (BookChapter chapter : book.chapters) {
            if (chapter.caf == null || !Files.exists(chapter.caf)) {
                Log.warn("audiobook", "chapter file missing, skipping: " + chapter.track.title);
                continue;
            }
            String title = isBlank(chapter.track.title)
                ? "Chapter " + (usable.size() + 1)
                : chapter.track.title;
            chapters.add(
                new AudiobookAssembler.Chapter(chapter.caf, title, chapter.track.durationMs)
            );
            usable.add(chapter);
        }
        if (chapters.isEmpty()) {
            Log.warn("audiobook", "no usable chapters for " + describe(book.author, book.title));
            return;
        }
        long duration = chapters.stream().mapToLong(AudiobookAssembler.Chapter::durationMs).sum();
        Path cover = usable
            .stream()
            .map(c -> c.cover)
            .filter(path -> path != null && Files.exists(path))
            .findFirst()
            .orElse(null);
        String baseName = library.uniqueBaseName(
            sanitize(book.author.isBlank() ? book.title : book.author + " - " + book.title),
            ".m4b"
        );
        Path output = library.libraryDir().resolve(baseName + ".m4b");
        Path splitWork = null;
        try {
            Files.createDirectories(library.libraryDir());
            if (settings.audiobookSplitLongTracks) {
                splitWork = Files.createTempDirectory(dirs.tmpDir(), "book-");
                chapters = planChapters(usable, chapters, duration, splitWork);
            }
            new AudiobookAssembler(settings.ffmpegPath, settings.audiobookBitrateKbps).assemble(
                chapters,
                cover,
                output,
                book.title,
                book.author
            );
        } catch (Exception e) {
            Log.error("audiobook", "cannot assemble " + book.title + ": " + e.getMessage());
            ui.lastError = "audiobook assembly failed: " + e.getMessage();
            return; // keep the CAFs for diagnosis / a later retry
        } finally {
            if (splitWork != null) {
                deleteTree(splitWork);
            }
        }

        LibraryTrack track = new LibraryTrack();
        track.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        track.bookId = book.id;
        track.title = book.title;
        track.artist = book.author;
        track.album = book.title;
        track.genre = "Audiobook";
        track.mediaKind = 8;
        track.durationMs = duration;
        track.expectedDurationMs = usable
            .stream()
            .mapToLong(chapter ->
                Math.max(chapter.track.durationMs, chapter.track.expectedDurationMs)
            )
            .sum();
        track.incomplete = usable.stream().anyMatch(chapter -> chapter.track.incomplete);
        track.recordedAtEpochMs = usable.get(0).track.recordedAtEpochMs;
        track.source = clientIp;
        track.baseName = baseName;
        track.status = "ready";
        track.chapterCount = chapters.size();
        track.files = new LinkedHashMap<>();
        track.files.put("m4b", output.getFileName().toString());
        try {
            track.sizeBytes = Files.size(output);
        } catch (IOException ignored) {
            track.sizeBytes = 0;
        }
        if (cover != null) {
            try {
                boolean png = cover
                    .getFileName()
                    .toString()
                    .toLowerCase(Locale.ROOT)
                    .endsWith(".png");
                Path target = library.coverFile(track.id, png);
                Files.createDirectories(target.getParent());
                Files.copy(cover, target, StandardCopyOption.REPLACE_EXISTING);
                track.cover = true;
                track.coverHash = CoverHash.of(target);
            } catch (IOException e) {
                Log.warn("audiobook", "cannot copy cover: " + e.getMessage());
            }
        }
        library.add(track);
        Log.info(
            "export",
            "audiobook ready: " +
                baseName +
                " (" +
                chapters.size() +
                " chapters, " +
                formatDuration(duration) +
                ")"
        );

        if (settings.keepRaw) {
            for (BookChapter chapter : usable) {
                Path target = library.libraryDir().resolve(chapter.track.baseName + ".caf");
                try {
                    move(chapter.caf, target);
                } catch (IOException e) {
                    Log.warn(
                        "audiobook",
                        "cannot keep raw chapter " + chapter.caf + ": " + e.getMessage()
                    );
                }
            }
        } else {
            for (BookChapter chapter : usable) {
                deleteQuietly(chapter.caf);
            }
        }
        if (!settings.audiobookKeepChapters) {
            for (BookChapter chapter : usable) {
                library.delete(chapter.track);
            }
            Log.info("library", "removed " + usable.size() + " chapter files (keepChapters=false)");
        }
        ui.changed();
    }

    /**
     * Builds the chapter list for the M4B. Existing chapter marks from sender metadata are moved
     * into the nearest pause, and a single long recording is split at long pauses.
     */
    private List<AudiobookAssembler.Chapter> planChapters(
        List<BookChapter> usable,
        List<AudiobookAssembler.Chapter> original,
        long totalMs,
        Path workDir
    ) {
        if (original.isEmpty()) {
            return original;
        }
        if (usable.size() == 1 && totalMs < 2 * ChapterSplitter.MIN_CHAPTER_MS) {
            return original;
        }
        try {
            ChapterSplitter splitter = new ChapterSplitter(settings.ffmpegPath);
            Path source;
            if (usable.size() == 1) {
                source = usable.get(0).caf();
            } else {
                List<Path> files = new ArrayList<>();
                for (BookChapter chapter : usable) {
                    files.add(chapter.caf());
                }
                source = splitter.concatenate(files, workDir.resolve("book.caf"), workDir);
            }
            List<ChapterSplitter.Silence> silences = splitter.detectSilences(source, totalMs);
            List<Long> boundaries;
            if (usable.size() == 1) {
                boundaries = splitter.silenceBoundaries(silences, totalMs);
            } else {
                List<Long> targets = new ArrayList<>();
                long at = 0;
                for (int i = 0; i < original.size() - 1; i++) {
                    at += original.get(i).durationMs();
                    targets.add(at);
                }
                boundaries = splitter.snapToSilence(targets, silences, totalMs);
            }
            if (boundaries.isEmpty()) {
                return original;
            }
            List<ChapterSplitter.Segment> segments = splitter.split(
                source,
                boundaries,
                totalMs,
                workDir,
                "chapter"
            );
            List<AudiobookAssembler.Chapter> result = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                String title =
                    usable.size() == 1
                        ? "Chapter " + (i + 1)
                        : i < original.size()
                          ? original.get(i).title()
                          : "Chapter " + (i + 1);
                result.add(
                    new AudiobookAssembler.Chapter(
                        segments.get(i).file(),
                        title,
                        segments.get(i).durationMs()
                    )
                );
            }
            if (usable.size() > 1 && result.size() != original.size()) {
                Log.warn("audiobook", "chapter boundaries collapsed; keeping sender chapters");
                return original;
            }
            Log.info(
                "audiobook",
                "chapter plan: " +
                    original.size() +
                    " -> " +
                    result.size() +
                    " chapters (" +
                    boundaries.size() +
                    " boundaries, " +
                    silences.size() +
                    " pauses found)"
            );
            return result;
        } catch (Exception e) {
            Log.warn(
                "audiobook",
                "chapter splitting failed, keeping sender chapters: " + e.getMessage()
            );
            return original;
        }
    }

    private static void deleteTree(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private record BookChapter(LibraryTrack track, Path caf, Path cover) {}

    private static final class Book {

        final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final String key;
        final String title;
        final String author;
        final List<BookChapter> chapters = new ArrayList<>();
        long lastChapterAt;

        Book(String key, String title, String author) {
            this.key = key;
            this.title = title;
            this.author = author;
        }
    }

    private boolean needsEnrichment(LibraryTrack track) {
        return (
            settings.metadataEnrichmentEnabled &&
            !track.audiobookChapter &&
            !(track.genre != null && track.genre.toLowerCase(Locale.ROOT).contains("podcast")) &&
            (isBlank(track.album) ||
                isBlank(track.genre) ||
                track.year == null ||
                track.trackNumber == null ||
                !track.cover)
        );
    }

    private Path enrich(LibraryTrack track, Path currentCover) {
        Optional<MetadataEnricher.Result> found = metadataEnricher.enrich(
            track.title,
            track.artist,
            track.album,
            track.trackNumber
        );
        if (found.isEmpty()) {
            return currentCover;
        }
        MetadataEnricher.Result result = found.get();
        boolean changed = false;
        if (isBlank(track.album) && !isBlank(result.album())) {
            track.album = result.album();
            changed = true;
        }
        if (isBlank(track.genre) && !isBlank(result.genre())) {
            track.genre = result.genre();
            changed = true;
        }
        if (track.year == null && result.year() != null) {
            track.year = result.year();
            changed = true;
        }
        if (track.trackNumber == null && result.trackNumber() != null) {
            track.trackNumber = result.trackNumber();
            changed = true;
        }
        Path cover = currentCover;
        if (cover == null && !track.cover && result.artworkUrl() != null) {
            Path artwork = library.coverFile(track.id, false);
            if (metadataEnricher.downloadArtwork(result.artworkUrl(), artwork)) {
                cover = artwork;
                track.cover = true;
                track.coverHash = CoverHash.of(artwork);
                changed = true;
            }
        }
        if (changed) {
            track.metadataSource = "apple";
            library.save();
            Log.info("lookup", "completed metadata for '" + track.title + "' from Apple");
        }
        return cover;
    }

    private static void move(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private String preferredExtension() {
        for (String format : settings.formats) {
            switch (format) {
                case "m4a" -> {
                    return ".m4a";
                }
                case "mp3" -> {
                    return ".mp3";
                }
                case "alac" -> {
                    return ".m4a";
                }
                default -> {
                }
            }
        }
        return ".caf";
    }

    private String suggestName(Draft draft) {
        String title = isBlank(draft.title) ? null : draft.title;
        String artist = isBlank(draft.artist) ? null : draft.artist;
        if (title != null && artist != null) {
            return artist + " - " + title;
        }
        if (title != null) {
            return title;
        }
        if (artist != null) {
            return (
                artist +
                " - " +
                new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(new Date())
            );
        }
        return (
            "Untitled " +
            new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date())
        );
    }

    private static String sanitize(String name) {
        String cleaned = INVALID_FILENAME.matcher(name)
            .replaceAll("_")
            .replaceAll("\\s+", " ")
            .trim();
        cleaned = cleaned.replaceAll("^\\.+", "").trim();
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120).trim();
        }
        return cleaned.isEmpty() ? "Untitled" : cleaned;
    }

    private void publishNow(Draft draft) {
        if (draft == null) {
            ui.nowTitle = "";
            ui.nowArtist = "";
            ui.nowAlbum = "";
            ui.nowStartedAtMs = 0;
            ui.nowRecordedMs = 0;
            ui.nowDurationMs = 0;
            clearLiveCover();
            return;
        }
        ui.nowTitle = draft.title == null ? "" : draft.title;
        ui.nowArtist = draft.artist == null ? "" : draft.artist;
        ui.nowAlbum = draft.album == null ? "" : draft.album;
        ui.nowStartedAtMs = draft.startedAt;
        ui.nowRecordedMs = draft.recordedMillis();
        ui.nowDurationMs = draft.durationHintMs;
    }

    private void writeLiveCover(byte[] image, String contentType) {
        try {
            boolean png =
                contentType != null && contentType.toLowerCase(Locale.ROOT).contains("png");
            Path target = dirs.coverDir().resolve(png ? "now.png" : "now.jpg");
            // Replace the file in one step: a page asking for it mid-write must not get half.
            Path partial = dirs.coverDir().resolve("now.partial");
            Files.write(partial, image);
            Files.move(
                partial,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
            Files.deleteIfExists(dirs.coverDir().resolve(png ? "now.jpg" : "now.png"));
            ui.coverVersion = COVER_VERSIONS.incrementAndGet();
        } catch (IOException e) {
            Log.warn("recorder", "cannot store live artwork: " + e.getMessage());
        }
    }

    private void clearLiveCover() {
        deleteQuietly(dirs.coverDir().resolve("now.jpg"));
        deleteQuietly(dirs.coverDir().resolve("now.png"));
        ui.coverVersion = 0;
    }

    private static String clean(String value) {
        return value == null ? null : value.replace('\u0000', ' ').trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String describe(String artist, String title) {
        String value =
            (isBlank(artist) ? "" : artist + " - ") + (isBlank(title) ? "unknown title" : title);
        return value.replaceAll("\\s+", " ").trim();
    }

    /** A recording is incomplete when it is clearly shorter than the duration the sender reported. */
    static boolean isIncomplete(long recordedMs, long expectedMs) {
        if (expectedMs <= 0 || recordedMs <= 0) {
            return false;
        }
        long tolerance = Math.max(2_000, expectedMs / 100);
        return recordedMs + tolerance < expectedMs;
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static final class Draft {

        String id;
        AlacConfig config;
        CafWriter writer;
        Path caf;
        String title;
        String artist;
        String album;
        String genre;
        Integer trackNumber;
        Integer year;
        byte[] cover;
        String coverMime;
        long frames;
        long startRtpTime = -1;
        long startedAt;
        long lastAudioAt;
        long durationHintMs;
        int gaps;
        int gapPackets;
        int filledPackets;
        int recoveredGaps;
        long maxRecoveryMs;
        boolean started;
        boolean failed;
        Integer mediaKind;

        long recordedMillis() {
            if (config == null || config.sampleRate() <= 0) {
                return 0;
            }
            return Math.round((frames * 1000.0) / config.sampleRate());
        }

        boolean matches(String title, String artist, String album) {
            return (
                compatible(this.title, title) &&
                compatible(this.artist, artist) &&
                compatible(this.album, album)
            );
        }

        private static boolean compatible(String current, String incoming) {
            if (incoming == null || incoming.isBlank()) {
                return true;
            }
            return current != null && current.trim().equalsIgnoreCase(incoming.trim());
        }
    }
}
