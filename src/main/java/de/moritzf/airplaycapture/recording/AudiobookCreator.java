package de.moritzf.airplaycapture.recording;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.library.CoverHash;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.media.AudiobookAssembler;
import de.moritzf.airplaycapture.media.AudiobookLookup;
import de.moritzf.airplaycapture.media.ChapterCatalog;
import de.moritzf.airplaycapture.media.ChapterSplitter;
import de.moritzf.airplaycapture.media.FfmpegRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Builds an .m4b from recordings that already exist in the library, and previews the exact chapter
 * plan before anything is written to the library.
 *
 * <p>The best available source per recording is used: ALAC, then M4A, then MP3, then CAF. Mixed
 * formats are normalised to PCM CAF before concatenation. A preview keeps the analysed work files
 * for one hour so the same plan can be assembled after the user checked it.
 */
public final class AudiobookCreator {

    private static final Pattern INVALID_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final String[] SOURCE_FORMATS = { "alac", "m4a", "mp3", "caf" };
    private static final long PREVIEW_TTL_MS = 60 * 60 * 1000;

    public enum ChapterMode {
        /** Chapter names and markers from a catalog, aligned to the recording. */
        CATALOG,
        /** Split at detected pauses. */
        PAUSES,
        /** Every selected recording becomes one chapter. */
        RECORDINGS,
    }

    public record Request(
        String groupKey,
        String title,
        String album,
        String author,
        List<LibraryTrack> sources,
        ChapterMode chapterMode,
        int bitrateKbps,
        ChapterCatalog.Result catalogChapters
    ) {
        public Request(
            String groupKey,
            String title,
            String album,
            String author,
            List<LibraryTrack> sources,
            ChapterMode chapterMode,
            int bitrateKbps
        ) {
            this(groupKey, title, album, author, sources, chapterMode, bitrateKbps, null);
        }

        /** Backwards-compatible constructor for callers that only know the split flag. */
        public Request(
            String groupKey,
            String title,
            String author,
            List<LibraryTrack> sources,
            boolean splitLongRecordings,
            int bitrateKbps
        ) {
            this(
                groupKey,
                title,
                null,
                author,
                sources,
                splitLongRecordings ? ChapterMode.PAUSES : ChapterMode.RECORDINGS,
                bitrateKbps,
                null
            );
        }
    }

    /** One catalog edition shown in the picker, with or without chapter data. */
    public record CatalogMatch(
        String id,
        String source,
        String title,
        String author,
        Integer year,
        String coverUrl,
        long durationMs,
        int chapterCount,
        boolean hasChapters,
        double score,
        ChapterCatalog.Result chapters
    ) {
        public boolean fits(long recordingMs) {
            return durationDeviation(recordingMs) <= ChapterSplitter.CATALOG_TOLERANCE;
        }

        public double durationDeviation(long recordingMs) {
            if (!hasChapters || durationMs <= 0 || recordingMs <= 0) {
                return Double.MAX_VALUE;
            }
            return Math.abs(durationMs - recordingMs) / (double) recordingMs;
        }
    }

    /**
     * One planned chapter. Its boundary sample and full playback are served under
     * {@code api/audiobook-preview/<preview id>/<index>} and {@code .../full}.
     */
    public record ChapterPreview(int index, String title, long startMs, long durationMs) {}

    public record Preview(
        String id,
        long durationMs,
        List<ChapterPreview> chapters,
        String source,
        String note
    ) {}

    /** What a finished conversion produced, for the queue entry and its download button. */
    public record Result(
        String trackId,
        String fileName,
        long sizeBytes,
        int chapterCount,
        long durationMs,
        String title,
        String author
    ) {}

    private final AppDirs dirs;
    private final Library library;
    private final Settings settings;
    private final AudiobookLookup lookup = new AudiobookLookup();
    private final ChapterCatalog catalog;
    private final Map<String, Analysis> analyses = new ConcurrentHashMap<>();

    public AudiobookCreator(AppDirs dirs, Library library, UiState ui, Settings settings) {
        this(dirs, library, ui, settings, new ChapterCatalog());
    }

    public AudiobookCreator(
        AppDirs dirs,
        Library library,
        UiState ui,
        Settings settings,
        ChapterCatalog catalog
    ) {
        this.dirs = dirs;
        this.library = library;
        this.settings = settings;
        this.catalog = catalog;
    }

    /** Analyses sources and returns the chapter plan without writing any library file. */
    public Preview analyze(Request request) throws IOException, InterruptedException {
        cleanupExpired();
        Analysis analysis = analyzeInternal(request, null);
        analyses.put(analysis.id, analysis);
        Log.info(
            "audiobook",
            "preview ready: " + analysis.chapters.size() + " chapters (" + analysis.id + ")"
        );
        return view(analysis);
    }

    /**
     * Generates (and caches) the short boundary clip for one planned chapter: the first 10 s and
     * the last 8 s of the chapter, or the whole chapter when it is shorter than 20 s.
     */
    public Path previewClip(String previewId, int index) throws IOException, InterruptedException {
        Analysis analysis = analyses.get(previewId);
        if (analysis == null || index < 0 || index >= analysis.chapters.size()) {
            return null;
        }
        synchronized (analysis) {
            PlannedChapter chapter = analysis.chapters.get(index);
            Path clip = analysis.work.resolve("preview-" + index + ".m4a");
            if (Files.exists(clip) && Files.size(clip) > 0) {
                return clip;
            }
            Files.deleteIfExists(clip);
            writeClip(chapter, clip);
            return Files.exists(clip) ? clip : null;
        }
    }

    /**
     * Full audio for one planned chapter. Chapters that are whole source recordings are served
     * directly; chapters produced by a split are encoded on first request and cached in the
     * temporary preview folder.
     */
    public Path fullChapter(String previewId, int index) throws IOException, InterruptedException {
        Analysis analysis = analyses.get(previewId);
        if (analysis == null || index < 0 || index >= analysis.chapters.size()) {
            return null;
        }
        synchronized (analysis) {
            PlannedChapter chapter = analysis.chapters.get(index);
            if (chapter.track() != null && chapter.startMs() == 0) {
                Path original = library.file(chapter.track(), chapter.format());
                if (original != null && Files.exists(original)) {
                    return original;
                }
            }
            Path full = analysis.work.resolve("full-" + index + ".m4a");
            if (Files.exists(full) && Files.size(full) > 0) {
                return full;
            }
            Files.deleteIfExists(full);
            run(
                List.of(
                    settings.ffmpegPath,
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-ss",
                    format(chapter.startMs() / 1000.0),
                    "-t",
                    format(chapter.durationMs() / 1000.0),
                    "-i",
                    chapter.source().toString(),
                    "-map",
                    "0:a:0",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "96k",
                    "-movflags",
                    "+faststart",
                    full.toString()
                )
            );
            return Files.exists(full) ? full : null;
        }
    }

    public void discard(String previewId) {
        Analysis analysis = previewId == null ? null : analyses.remove(previewId);
        if (analysis != null) {
            deleteTree(analysis.work);
        }
    }

    /**
     * Analyse + assemble in one blocking call, reporting progress to the queue. Runs on the
     * queue worker thread; the caller owns scheduling and error handling.
     */
    public Result convert(Request request, AudiobookJobs.Progress progress)
        throws IOException, InterruptedException {
        Analysis analysis = null;
        try {
            analysis = analyzeInternal(request, progress);
            return assemble(
                analysis,
                request.title(),
                request.author(),
                request.bitrateKbps(),
                progress
            );
        } finally {
            if (analysis != null) {
                deleteTree(analysis.work);
            }
        }
    }

    /** Assemble from an already previewed plan, so the result matches what the user checked. */
    public Result convert(
        String previewId,
        String title,
        String author,
        int bitrateKbps,
        AudiobookJobs.Progress progress
    ) throws IOException, InterruptedException {
        Analysis analysis = analyses.remove(previewId);
        if (analysis == null) {
            throw new IOException(
                "The preview expired. Open the dialog again and create a new one."
            );
        }
        try {
            return assemble(analysis, title, author, bitrateKbps, progress);
        } finally {
            deleteTree(analysis.work);
        }
    }

    public Optional<AudiobookLookup.Verdict> lookup(String title, String album, String author) {
        return lookup.lookup(title, album, author, lookupOptions());
    }

    private AudiobookLookup.Options lookupOptions() {
        return new AudiobookLookup.Options(
            settings.audiobookLookupEnabled,
            settings.audiobookLookupMusicBrainz,
            settings.audiobookLookupCountry,
            settings.audiobookLookupContact
        );
    }

    /** Fetches chapter names and durations from Deezer/MusicBrainz for the given recording length. */
    public Optional<ChapterCatalog.Result> lookupChapters(
        String title,
        String album,
        String author,
        long expectedDurationMs
    ) {
        return catalog.lookup(title, album, author, expectedDurationMs, catalogOptions());
    }

    /** All matching editions for the picker: chapter editions first, Apple metadata after them. */
    public List<CatalogMatch> searchCatalog(
        String title,
        String album,
        String author,
        long expectedDurationMs
    ) {
        List<CatalogMatch> matches = new ArrayList<>();
        for (ChapterCatalog.Result result : catalog.search(
            title,
            album,
            author,
            expectedDurationMs,
            catalogOptions()
        )) {
            matches.add(
                new CatalogMatch(
                    result.id(),
                    result.source(),
                    result.album(),
                    result.artist(),
                    result.year(),
                    result.coverUrl(),
                    result.totalMs(),
                    result.chapters().size(),
                    true,
                    result.score(),
                    result
                )
            );
        }
        int apple = 0;
        for (AudiobookLookup.Edition edition : lookup.search(
            title,
            album,
            author,
            lookupOptions()
        )) {
            if (apple++ >= 3) {
                break;
            }
            matches.add(
                new CatalogMatch(
                    edition.id(),
                    "Apple Books",
                    edition.title(),
                    edition.author(),
                    edition.year(),
                    edition.artworkUrl(),
                    0,
                    0,
                    false,
                    0,
                    null
                )
            );
        }
        matches.sort(
            Comparator.comparing(CatalogMatch::hasChapters)
                .reversed()
                .thenComparingDouble(match -> match.durationDeviation(expectedDurationMs))
                .thenComparing(Comparator.comparingDouble(CatalogMatch::score).reversed())
        );
        return matches.size() > 8 ? List.copyOf(matches.subList(0, 8)) : List.copyOf(matches);
    }

    private ChapterCatalog.Options catalogOptions() {
        boolean enabled = settings.audiobookCatalogChapters;
        return new ChapterCatalog.Options(
            enabled,
            enabled && settings.audiobookLookupMusicBrainz,
            settings.audiobookLookupContact
        );
    }

    // ---------------------------------------------------------------- analysis

    private Analysis analyzeInternal(Request request, AudiobookJobs.Progress progress)
        throws IOException, InterruptedException {
        report(progress, "Reading recordings", 0.04);
        List<LibraryTrack> requested = request
            .sources()
            .stream()
            .filter(track -> track != null && track.files != null && track.chapterCount == null)
            .toList();
        List<LibraryTrack> tracks = LibraryGroups.bestTakes(requested)
            .stream()
            .sorted(
                Comparator.comparingInt((LibraryTrack track) ->
                    track.trackNumber == null ? Integer.MAX_VALUE : track.trackNumber
                ).thenComparingLong(track -> track.recordedAtEpochMs)
            )
            .toList();
        if (tracks.size() < requested.size()) {
            Log.info(
                "audiobook",
                "ignoring " +
                    (requested.size() - tracks.size()) +
                    " duplicate take(s) during audiobook creation"
            );
        }
        if (tracks.isEmpty()) {
            throw new IOException("no source recordings selected");
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Path work = Files.createTempDirectory(dirs.tmpDir(), "book-preview-");
        try {
            List<Source> sources = selectSources(tracks);
            if (sources.isEmpty()) {
                throw new IOException("no playable source file found");
            }
            if (sources.stream().map(Source::format).distinct().count() > 1) {
                report(progress, "Normalising " + sources.size() + " recordings", 0.10);
                sources = normalize(sources, work);
            }
            List<String> titles = sourceChapterTitles(sources);
            long totalMs = sources
                .stream()
                .mapToLong(source -> source.track().durationMs)
                .sum();

            ChapterSplitter splitter = new ChapterSplitter(settings.ffmpegPath);
            ChapterMode mode =
                request.chapterMode() == null ? ChapterMode.PAUSES : request.chapterMode();
            Path combined = null;
            List<ChapterSplitter.Silence> silences = null;
            List<Long> boundaries = List.of();
            List<PlannedChapter> chapters = List.of();
            String source = "recordings";
            String note = "";
            long startMs = 0;
            long endMs = totalMs;

            if (mode == ChapterMode.CATALOG) {
                report(progress, "Looking up catalog chapters", 0.14);
                Optional<ChapterCatalog.Result> found =
                    request.catalogChapters() != null
                        ? Optional.of(request.catalogChapters())
                        : catalog.lookup(
                              request.title(),
                              request.album(),
                              request.author(),
                              totalMs,
                              catalogOptions()
                          );
                if (found.isEmpty()) {
                    note = "No catalog chapter list found; using pauses instead.";
                } else {
                    List<ChapterCatalog.Chapter> chaptersFromCatalog = found.get().chapters();
                    List<Long> durations = chaptersFromCatalog
                        .stream()
                        .map(ChapterCatalog.Chapter::durationMs)
                        .toList();
                    report(
                        progress,
                        "Joining " +
                            sources.size() +
                            (sources.size() == 1 ? " recording" : " recordings"),
                        0.20
                    );
                    combined = concatenate(splitter, sources, work);
                    report(progress, "Detecting pauses for chapter cuts", 0.30);
                    silences = splitter.detectSilences(combined, totalMs);
                    ChapterSplitter.CatalogPlan plan = splitter.catalogPlan(
                        durations,
                        totalMs,
                        silences
                    );
                    if (plan.boundaries().size() == durations.size() - 1) {
                        chapters = catalogChapters(chaptersFromCatalog, plan, combined);
                        boundaries = plan.boundaries();
                        source = found.get().source();
                        note = found.get().album();
                        startMs = plan.startMs();
                        endMs = plan.endMs();
                        if (startMs > 0 || endMs < totalMs) {
                            note +=
                                " · trimmed " +
                                clock(startMs) +
                                " intro / " +
                                clock(totalMs - endMs) +
                                " outro";
                        }
                    } else {
                        note =
                            "Catalog chapters do not fit this recording (" +
                            found.get().source() +
                            " has " +
                            Math.round(found.get().totalMs() / 60_000.0) +
                            " min); using pauses instead.";
                        boundaries = List.of();
                    }
                }
                if (chapters.isEmpty()) {
                    mode = ChapterMode.PAUSES;
                }
            }

            if (mode == ChapterMode.PAUSES) {
                if (combined == null) {
                    report(
                        progress,
                        "Joining " +
                            sources.size() +
                            (sources.size() == 1 ? " recording" : " recordings"),
                        0.20
                    );
                    combined = concatenate(splitter, sources, work);
                }
                if (silences == null) {
                    report(progress, "Detecting pauses for chapter cuts", 0.30);
                    silences = splitter.detectSilences(combined, totalMs);
                }
                boundaries = planBoundaries(splitter, sources, silences, totalMs);
                chapters = boundaries.isEmpty()
                    ? sourceChapters(sources, titles)
                    : boundaryChapters(sources, titles, boundaries, totalMs, combined);
                source = boundaries.isEmpty() ? "recordings" : "pauses";
            } else if (mode == ChapterMode.RECORDINGS) {
                chapters = sourceChapters(sources, titles);
                source = "recordings";
            }
            if (chapters.isEmpty()) {
                throw new IOException("no chapters to assemble");
            }
            // Chapters without a source recording are ranges of the joined book and must be cut
            // out of it; passing the joined file per chapter would repeat the whole book.
            boolean split =
                combined != null && chapters.stream().anyMatch(chapter -> chapter.track() == null);
            return new Analysis(
                id,
                work,
                tracks,
                sources,
                split,
                combined,
                boundaries,
                chapters,
                startMs,
                endMs,
                source,
                note
            );
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteTree(work);
            throw e;
        }
    }

    private static Path concatenate(ChapterSplitter splitter, List<Source> sources, Path work)
        throws IOException, InterruptedException {
        return splitter.concatenate(
            sources.stream().map(Source::path).toList(),
            work.resolve("book.caf"),
            work
        );
    }

    private static List<Long> planBoundaries(
        ChapterSplitter splitter,
        List<Source> sources,
        List<ChapterSplitter.Silence> silences,
        long totalMs
    ) {
        if (sources.size() == 1) {
            return splitter.silenceBoundaries(silences, totalMs);
        }
        List<Long> targets = new ArrayList<>();
        long at = 0;
        for (int i = 0; i < sources.size() - 1; i++) {
            at += sources.get(i).track().durationMs;
            targets.add(at);
        }
        return splitter.snapToSilence(targets, silences, totalMs);
    }

    private static List<PlannedChapter> sourceChapters(List<Source> sources, List<String> titles) {
        List<PlannedChapter> chapters = new ArrayList<>();
        long at = 0;
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            long duration = Math.max(0, source.track().durationMs);
            chapters.add(
                new PlannedChapter(
                    titles.get(i),
                    at,
                    0,
                    duration,
                    source.path(),
                    source.track(),
                    source.format()
                )
            );
            at += duration;
        }
        return chapters;
    }

    private static List<PlannedChapter> boundaryChapters(
        List<Source> sources,
        List<String> titles,
        List<Long> boundaries,
        long totalMs,
        Path combined
    ) {
        List<Long> cuts = new ArrayList<>();
        cuts.add(0L);
        cuts.addAll(boundaries);
        cuts.add(totalMs);
        List<PlannedChapter> chapters = new ArrayList<>();
        for (int i = 0; i < cuts.size() - 1; i++) {
            long start = cuts.get(i);
            long duration = cuts.get(i + 1) - start;
            if (duration <= 0) {
                continue;
            }
            String title =
                sources.size() == 1
                    ? "Chapter " + (chapters.size() + 1)
                    : i < titles.size()
                      ? titles.get(i)
                      : "Chapter " + (chapters.size() + 1);
            chapters.add(new PlannedChapter(title, start, start, duration, combined, null, "m4a"));
        }
        return chapters;
    }

    private static List<PlannedChapter> catalogChapters(
        List<ChapterCatalog.Chapter> catalog,
        ChapterSplitter.CatalogPlan plan,
        Path combined
    ) {
        List<Long> cuts = new ArrayList<>();
        cuts.add(plan.startMs());
        cuts.addAll(plan.boundaries());
        cuts.add(plan.endMs());
        List<PlannedChapter> chapters = new ArrayList<>();
        for (int i = 0; i < cuts.size() - 1; i++) {
            long start = cuts.get(i);
            long duration = cuts.get(i + 1) - start;
            if (duration <= 0) {
                continue;
            }
            String title =
                i < catalog.size() && !catalog.get(i).title().isBlank()
                    ? catalog.get(i).title()
                    : "Chapter " + (chapters.size() + 1);
            chapters.add(new PlannedChapter(title, start, start, duration, combined, null, "m4a"));
        }
        return chapters;
    }

    // ---------------------------------------------------------------- assembly

    private Result assemble(
        Analysis analysis,
        String title,
        String author,
        int bitrateKbps,
        AudiobookJobs.Progress progress
    ) throws IOException, InterruptedException {
        String bookTitle = blank(title) ? "Untitled audiobook" : title.trim();
        String bookAuthor = author == null ? "" : author.trim();

        List<AudiobookAssembler.Chapter> chapters = new ArrayList<>();
        if (analysis.split) {
            report(progress, "Cutting " + analysis.chapters.size() + " chapters", 0.36);
            List<ChapterSplitter.Segment> segments = new ChapterSplitter(settings.ffmpegPath).split(
                analysis.combined,
                analysis.startMs,
                analysis.endMs,
                analysis.boundaries,
                analysis.work,
                "chapter"
            );
            for (int i = 0; i < segments.size(); i++) {
                String chapterTitle =
                    i < analysis.chapters.size()
                        ? analysis.chapters.get(i).title()
                        : "Chapter " + (i + 1);
                chapters.add(
                    new AudiobookAssembler.Chapter(
                        segments.get(i).file(),
                        chapterTitle,
                        segments.get(i).durationMs()
                    )
                );
            }
        } else {
            for (PlannedChapter chapter : analysis.chapters) {
                chapters.add(
                    new AudiobookAssembler.Chapter(
                        chapter.source(),
                        chapter.title(),
                        chapter.durationMs()
                    )
                );
            }
        }
        if (chapters.isEmpty()) {
            throw new IOException("no chapters to assemble");
        }

        long duration = chapters.stream().mapToLong(AudiobookAssembler.Chapter::durationMs).sum();
        Path cover = cover(analysis.tracks);
        String baseName = library.uniqueBaseName(
            sanitize(bookAuthor.isBlank() ? bookTitle : bookAuthor + " - " + bookTitle),
            ".m4b"
        );
        Path output = library.libraryDir().resolve(baseName + ".m4b");
        Files.createDirectories(library.libraryDir());
        report(
            progress,
            "Encoding " + chapters.size() + "-chapter audiobook at " + bitrateKbps + " kbps",
            0.45
        );
        new AudiobookAssembler(settings.ffmpegPath, bitrateKbps).assemble(
            chapters,
            cover,
            output,
            bookTitle,
            bookAuthor,
            fraction -> {
                // runs on ffmpeg's reader thread: report only, never throw here
                if (progress != null && !progress.cancelled()) {
                    progress.step(
                        "Encoding audiobook at " + bitrateKbps + " kbps",
                        0.45 + 0.50 * fraction
                    );
                }
            },
            progress == null ? null : progress::cancelled
        );

        // A rebuild joins the existing book instead of splitting it into a second group; the new
        // file becomes the current download and the old one stays as an earlier version.
        String bookId = analysis.tracks
            .stream()
            .map(track -> track.bookId)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        LibraryTrack book = new LibraryTrack();
        book.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        book.bookId = bookId;
        book.title = bookTitle;
        book.artist = bookAuthor;
        book.album = bookTitle;
        book.genre = "Audiobook";
        book.mediaKind = 8;
        book.durationMs = duration;
        book.expectedDurationMs = analysis.tracks
            .stream()
            .mapToLong(track -> Math.max(track.durationMs, track.expectedDurationMs))
            .sum();
        book.incomplete = analysis.tracks.stream().anyMatch(track -> track.incomplete);
        book.recordedAtEpochMs = analysis.tracks
            .stream()
            .mapToLong(track -> track.recordedAtEpochMs)
            .min()
            .orElse(System.currentTimeMillis());
        book.source = analysis.tracks
            .stream()
            .map(track -> track.source)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("");
        book.baseName = baseName;
        book.status = "ready";
        book.chapterCount = chapters.size();
        book.createdAtEpochMs = System.currentTimeMillis();
        book.files = new java.util.LinkedHashMap<>();
        book.files.put("m4b", output.getFileName().toString());
        book.sizeBytes = Files.size(output);
        if (cover != null && Files.exists(cover)) {
            boolean png = cover.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
            Path target = library.coverFile(book.id, png);
            Files.createDirectories(target.getParent());
            Files.copy(cover, target, StandardCopyOption.REPLACE_EXISTING);
            book.cover = true;
            book.coverHash = CoverHash.of(target);
        }

        // Link sources to this book so the library shows them as one group.
        for (LibraryTrack track : analysis.tracks) {
            track.bookId = bookId;
            track.audiobookChapter = true;
        }
        library.add(book);
        Log.info(
            "audiobook",
            "manual audiobook ready: " +
                baseName +
                " (" +
                chapters.size() +
                " chapters, " +
                analysis.tracks.size() +
                " sources)"
        );
        if (progress != null) {
            // the book is saved: a late cancel must not turn a finished file into a failure
            progress.step("Ready to download", 0.99);
        }
        return new Result(
            book.id,
            output.getFileName().toString(),
            book.sizeBytes,
            chapters.size(),
            duration,
            bookTitle,
            bookAuthor
        );
    }

    /** Reports a step and stops the conversion here if the user cancelled it. */
    private static void report(AudiobookJobs.Progress progress, String step, double fraction) {
        if (progress == null) {
            return;
        }
        if (progress.cancelled()) {
            throw new java.util.concurrent.CancellationException("conversion cancelled");
        }
        progress.step(step, fraction);
    }

    // ---------------------------------------------------------------- preview clips

    private void writeClip(PlannedChapter chapter, Path clip)
        throws IOException, InterruptedException {
        double start = chapter.startMs() / 1000.0;
        double duration = chapter.durationMs() / 1000.0;
        if (duration <= 0) {
            return;
        }
        List<String> command = new ArrayList<>(
            List.of(settings.ffmpegPath, "-hide_banner", "-loglevel", "error", "-y")
        );
        if (duration <= 20.0) {
            command.addAll(
                List.of(
                    "-ss",
                    format(start),
                    "-t",
                    format(duration),
                    "-i",
                    chapter.source().toString(),
                    "-map",
                    "0:a:0",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "64k"
                )
            );
        } else {
            double endStart = start + duration - 8.0;
            command.addAll(
                List.of(
                    "-ss",
                    format(start),
                    "-t",
                    "10",
                    "-i",
                    chapter.source().toString(),
                    "-ss",
                    format(endStart),
                    "-t",
                    "8",
                    "-i",
                    chapter.source().toString(),
                    "-filter_complex",
                    "[0:a][1:a]concat=n=2:v=0:a=1[outa]",
                    "-map",
                    "[outa]",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "64k"
                )
            );
        }
        command.addAll(List.of("-movflags", "+faststart", clip.toString()));
        run(command);
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private static String clock(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------------------------------------------------------------- helpers

    private Preview view(Analysis analysis) {
        List<ChapterPreview> chapters = new ArrayList<>();
        for (int i = 0; i < analysis.chapters.size(); i++) {
            PlannedChapter chapter = analysis.chapters.get(i);
            chapters.add(
                new ChapterPreview(
                    i,
                    chapter.title(),
                    Math.max(0, chapter.timelineStartMs() - analysis.startMs),
                    chapter.durationMs()
                )
            );
        }
        return new Preview(
            analysis.id,
            analysis.totalMs(),
            List.copyOf(chapters),
            analysis.source,
            analysis.note
        );
    }

    private void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - PREVIEW_TTL_MS;
        for (Map.Entry<String, Analysis> entry : analyses.entrySet()) {
            if (entry.getValue().createdAt < cutoff) {
                Analysis removed = analyses.remove(entry.getKey());
                if (removed != null) {
                    deleteTree(removed.work);
                }
            }
        }
    }

    private List<Source> selectSources(List<LibraryTrack> tracks) {
        List<Source> sources = new ArrayList<>();
        for (LibraryTrack track : tracks) {
            String format = null;
            Path path = null;
            for (String candidate : SOURCE_FORMATS) {
                Path file = library.file(track, candidate);
                if (file != null && Files.exists(file)) {
                    format = candidate;
                    path = file;
                    break;
                }
            }
            if (path != null) {
                sources.add(new Source(track, path, format));
            }
        }
        return sources;
    }

    private List<Source> normalize(List<Source> sources, Path work)
        throws IOException, InterruptedException {
        List<Source> normalized = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            Path target = work.resolve("source-" + (i + 1) + ".caf");
            run(
                List.of(
                    settings.ffmpegPath,
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-i",
                    source.path().toString(),
                    "-map",
                    "0:a:0",
                    "-c:a",
                    "pcm_s16le",
                    target.toString()
                )
            );
            normalized.add(new Source(source.track(), target, "caf"));
        }
        return normalized;
    }

    private Path cover(List<LibraryTrack> tracks) {
        for (LibraryTrack track : tracks) {
            if (!track.cover) {
                continue;
            }
            Path jpeg = library.coverFile(track.id, false);
            if (Files.exists(jpeg)) {
                return jpeg;
            }
            Path png = library.coverFile(track.id, true);
            if (Files.exists(png)) {
                return png;
            }
        }
        return null;
    }

    private static String chapterTitle(LibraryTrack track) {
        return track.title == null || track.title.isBlank() ? track.displayName() : track.title;
    }

    /** Makes chapter titles distinct when several sources share the same title. */
    private static List<String> sourceChapterTitles(List<Source> sources) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Source source : sources) {
            counts.merge(chapterTitle(source.track()).toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<String> titles = new ArrayList<>();
        for (Source source : sources) {
            String title = chapterTitle(source.track());
            if (counts.getOrDefault(title.toLowerCase(Locale.ROOT), 0) > 1) {
                String artist = source.track().artist;
                title +=
                    " (" +
                    (artist == null || artist.isBlank() ? "Part " + (titles.size() + 1) : artist) +
                    ")";
            }
            titles.add(title);
        }
        return titles;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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

    private static void run(List<String> command) throws IOException, InterruptedException {
        FfmpegRunner.Result result = FfmpegRunner.run(command);
        if (result.exitCode() != 0) {
            throw new IOException("ffmpeg failed: " + result.output().strip());
        }
    }

    private static void deleteTree(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
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

    private record Source(LibraryTrack track, Path path, String format) {}

    private record PlannedChapter(
        String title,
        long timelineStartMs,
        long startMs,
        long durationMs,
        Path source,
        LibraryTrack track,
        String format
    ) {}

    private static final class Analysis {

        final String id;
        final Path work;
        final List<LibraryTrack> tracks;
        final List<Source> sources;
        final boolean split;
        final Path combined;
        final List<Long> boundaries;
        final List<PlannedChapter> chapters;
        final long startMs;
        final long endMs;
        final String source;
        final String note;
        final long createdAt = System.currentTimeMillis();

        Analysis(
            String id,
            Path work,
            List<LibraryTrack> tracks,
            List<Source> sources,
            boolean split,
            Path combined,
            List<Long> boundaries,
            List<PlannedChapter> chapters,
            long startMs,
            long endMs,
            String source,
            String note
        ) {
            this.id = id;
            this.work = work;
            this.tracks = tracks;
            this.sources = sources;
            this.split = split;
            this.combined = combined;
            this.boundaries = boundaries;
            this.chapters = chapters;
            this.startMs = startMs;
            this.endMs = endMs;
            this.source = source;
            this.note = note;
        }

        long totalMs() {
            return chapters.stream().mapToLong(PlannedChapter::durationMs).sum();
        }
    }
}
