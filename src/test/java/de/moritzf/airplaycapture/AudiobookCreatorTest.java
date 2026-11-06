package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.media.ChapterCatalog;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudiobookCreatorTest {

    @TempDir
    Path temp;

    /** Closed after each test, so no queue write races the temp folder cleanup. */
    private final List<AudiobookJobs> opened = new ArrayList<>();

    private AudiobookJobs open(AudiobookJobs jobs) {
        opened.add(jobs);
        return jobs;
    }

    @AfterEach
    void closeJobs() {
        opened.forEach(AudiobookJobs::close);
    }

    @Test
    void buildsAnAudiobookFromExistingM4aRecordings() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        AppDirs dirs = new AppDirs(temp.resolve("data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        settings.audiobookBitrateKbps = 96;
        Library library = new Library(dirs, settings);

        LibraryTrack first = track("one", "Chapter One", 2.0, library.libraryDir());
        LibraryTrack second = track("two", "Chapter Two", 3.0, library.libraryDir());
        library.add(first);
        library.add(second);

        UiState ui = new UiState();
        AudiobookCreator creator = new AudiobookCreator(dirs, library, ui, settings);
        AudiobookJobs jobs = open(new AudiobookJobs(dirs, ui));
        jobs.enqueue("group", "Test Book", "Test Author", progress ->
            creator.convert(
                new AudiobookCreator.Request(
                    "group",
                    "Test Book",
                    "Test Author",
                    List.of(first, second),
                    false,
                    96
                ),
                progress
            )
        );

        long deadline = System.currentTimeMillis() + 60_000;
        LibraryTrack book = null;
        while (System.currentTimeMillis() < deadline) {
            for (LibraryTrack candidate : library.tracks()) {
                if (candidate.files.containsKey("m4b") && "ready".equals(candidate.status)) {
                    book = candidate;
                    break;
                }
            }
            if (book != null) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }

        assertNotNull(
            book,
            "no m4b created: " +
                jobs
                    .latest("group")
                    .map(j -> j.error)
                    .orElse("")
        );
        assertEquals(2, book.chapterCount);
        assertEquals("Test Book", book.title);
        assertEquals("Test Author", book.artist);
        assertNotNull(book.bookId);
        assertEquals(book.bookId, first.bookId);
        assertEquals(book.bookId, second.bookId);
        Path output = library.file(book, "m4b");
        assertTrue(Files.exists(output));
        String chapters = TestMedia.ffprobe(
            output,
            "-show_chapters",
            "-show_entries",
            "chapter=start_time:chapter_tags=title",
            "-of",
            "json"
        );
        assertTrue(chapters.contains("Chapter One"), chapters);
        assertTrue(chapters.contains("Chapter Two"), chapters);
    }

    @Test
    void previewsEachChapterAndBuildsFromThePreviewedPlan() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        AppDirs dirs = new AppDirs(temp.resolve("preview-data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        Library library = new Library(dirs, settings);
        LibraryTrack first = track("one", "Chapter One", 2.0, library.libraryDir());
        LibraryTrack second = track("two", "Chapter Two", 3.0, library.libraryDir());
        library.add(first);
        library.add(second);

        AudiobookCreator creator = new AudiobookCreator(dirs, library, new UiState(), settings);
        AudiobookCreator.Request request = new AudiobookCreator.Request(
            "group",
            "Test Book",
            "Test Author",
            List.of(first, second),
            false,
            96
        );
        AudiobookCreator.Preview preview = creator.analyze(request);

        assertEquals(2, preview.chapters().size());
        assertEquals("Chapter One", preview.chapters().get(0).title());
        assertEquals("Chapter Two", preview.chapters().get(1).title());
        Path clip = creator.previewClip(preview.id(), 0);
        assertNotNull(clip);
        assertTrue(Files.exists(clip));
        String duration = TestMedia.ffprobe(
            clip,
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1"
        );
        assertTrue(Double.parseDouble(duration) > 1.5, duration);
        Path full = creator.fullChapter(preview.id(), 0);
        assertNotNull(full);
        assertTrue(Files.exists(full));
        assertTrue(full.getFileName().toString().endsWith(".m4a"), full.toString());

        AudiobookJobs jobs = open(new AudiobookJobs(dirs, new UiState()));
        jobs.enqueue("group", "Test Book", "Test Author", progress ->
            creator.convert(preview.id(), "Test Book", "Test Author", 96, progress)
        );
        long deadline = System.currentTimeMillis() + 60_000;
        LibraryTrack book = null;
        while (System.currentTimeMillis() < deadline) {
            for (LibraryTrack candidate : library.tracks()) {
                if (candidate.files.containsKey("m4b") && "ready".equals(candidate.status)) {
                    book = candidate;
                    break;
                }
            }
            if (book != null) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertNotNull(
            book,
            "no m4b created from preview: " +
                jobs
                    .latest("group")
                    .map(j -> j.error)
                    .orElse("")
        );
        assertEquals(2, book.chapterCount);
    }

    @Test
    void usesCatalogChapterNamesAndDurations() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        AppDirs dirs = new AppDirs(temp.resolve("catalog-data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        Library library = new Library(dirs, settings);
        LibraryTrack first = track("one", "Part One", 2.0, library.libraryDir());
        LibraryTrack second = track("two", "Part Two", 3.0, library.libraryDir());
        library.add(first);
        library.add(second);

        ChapterCatalog catalog = new ChapterCatalog() {
            @Override
            public Optional<ChapterCatalog.Result> lookup(
                String title,
                String album,
                String artist,
                long expectedDurationMs,
                ChapterCatalog.Options options
            ) {
                return Optional.of(
                    new ChapterCatalog.Result(
                        "Test catalog",
                        "Test Book",
                        "Test Author",
                        List.of(
                            new ChapterCatalog.Chapter("Opening", 2_000),
                            new ChapterCatalog.Chapter("Closing", 3_000)
                        )
                    )
                );
            }
        };
        AudiobookCreator creator = new AudiobookCreator(
            dirs,
            library,
            new UiState(),
            settings,
            catalog
        );
        AudiobookCreator.Request request = new AudiobookCreator.Request(
            "group",
            "Test Book",
            "Test Book",
            "Test Author",
            List.of(first, second),
            AudiobookCreator.ChapterMode.CATALOG,
            96
        );

        AudiobookCreator.Preview preview = creator.analyze(request);

        assertEquals(2, preview.chapters().size());
        assertEquals("Opening", preview.chapters().get(0).title());
        assertEquals("Closing", preview.chapters().get(1).title());
        assertEquals("Test catalog", preview.source());

        AudiobookJobs jobs = open(new AudiobookJobs(dirs, new UiState()));
        jobs.enqueue("group", "Test Book", "Test Author", progress ->
            creator.convert(preview.id(), "Test Book", "Test Author", 96, progress)
        );
        long deadline = System.currentTimeMillis() + 60_000;
        LibraryTrack book = null;
        while (System.currentTimeMillis() < deadline) {
            for (LibraryTrack candidate : library.tracks()) {
                if (candidate.files.containsKey("m4b") && "ready".equals(candidate.status)) {
                    book = candidate;
                    break;
                }
            }
            if (book != null) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertNotNull(
            book,
            "no m4b created from catalog chapters: " +
                jobs
                    .latest("group")
                    .map(j -> j.error)
                    .orElse("")
        );
        assertEquals(2, book.chapterCount);
        String chapters = TestMedia.ffprobe(
            library.file(book, "m4b"),
            "-show_chapters",
            "-show_entries",
            "chapter_tags=title",
            "-of",
            "json"
        );
        assertTrue(chapters.contains("Opening"), chapters);
        assertTrue(chapters.contains("Closing"), chapters);
        // Catalog chapters are cut from the joined book; the book must not be repeated per chapter.
        double seconds = Double.parseDouble(
            TestMedia.ffprobe(
                library.file(book, "m4b"),
                "-show_entries",
                "format=duration",
                "-of",
                "default=nw=1:nk=1"
            ).trim()
        );
        assertEquals(5.0, seconds, 0.5, "m4b duration");
    }

    @Test
    void usesTheEditionSelectedInThePicker() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        AppDirs dirs = new AppDirs(temp.resolve("selected-data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        Library library = new Library(dirs, settings);
        LibraryTrack first = track("one", "Part One", 2.0, library.libraryDir());
        LibraryTrack second = track("two", "Part Two", 3.0, library.libraryDir());
        library.add(first);
        library.add(second);

        ChapterCatalog catalog = new ChapterCatalog() {
            @Override
            public Optional<ChapterCatalog.Result> lookup(
                String title,
                String album,
                String artist,
                long expectedDurationMs,
                ChapterCatalog.Options options
            ) {
                return Optional.empty();
            }
        };
        ChapterCatalog.Result selected = new ChapterCatalog.Result(
            "Picked catalog",
            "Test Book",
            "Test Author",
            List.of(
                new ChapterCatalog.Chapter("Selected One", 2_000),
                new ChapterCatalog.Chapter("Selected Two", 3_000)
            )
        );
        AudiobookCreator creator = new AudiobookCreator(
            dirs,
            library,
            new UiState(),
            settings,
            catalog
        );
        AudiobookCreator.Request request = new AudiobookCreator.Request(
            "group",
            "Test Book",
            "Test Book",
            "Test Author",
            List.of(first, second),
            AudiobookCreator.ChapterMode.CATALOG,
            96,
            selected
        );

        AudiobookCreator.Preview preview = creator.analyze(request);

        assertEquals(2, preview.chapters().size());
        assertEquals("Selected One", preview.chapters().get(0).title());
        assertEquals("Selected Two", preview.chapters().get(1).title());
        assertEquals("Picked catalog", preview.source());
    }

    @Test
    void aRebuildStaysInTheSameBookAndBecomesTheCurrentDownload() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");

        AppDirs dirs = new AppDirs(temp.resolve("rebuild-data"));
        dirs.createDirectories();
        Settings settings = new Settings();
        Library library = new Library(dirs, settings);
        LibraryTrack first = track("one", "Chapter One", 2.0, library.libraryDir());
        LibraryTrack second = track("two", "Chapter Two", 3.0, library.libraryDir());
        first.audiobookChapter = true;
        second.audiobookChapter = true;
        library.add(first);
        library.add(second);
        AudiobookCreator creator = new AudiobookCreator(dirs, library, new UiState(), settings);
        AudiobookCreator.Request request = new AudiobookCreator.Request(
            "group",
            "Test Book",
            "Test Book",
            "Test Author",
            List.of(first, second),
            AudiobookCreator.ChapterMode.RECORDINGS,
            96
        );

        AudiobookCreator.Result original = creator.convert(request, null);
        TimeUnit.MILLISECONDS.sleep(5);
        AudiobookCreator.Result rebuilt = creator.convert(request, null);

        List<LibraryGroups.Group> groups = LibraryGroups.groups(library.tracks());
        assertEquals(1, groups.size(), "a rebuild must not split the book into a second group");
        LibraryGroups.Group group = groups.get(0);
        assertEquals(
            rebuilt.trackId(),
            group.m4b().orElseThrow().id,
            "newest build is the download"
        );
        LibraryTrack old = library.find(original.trackId()).orElseThrow();
        assertTrue(group.isEarlierVersion(old));
        assertEquals(old.bookId, first.bookId);
    }

    private LibraryTrack track(String id, String title, double seconds, Path libraryDir)
        throws Exception {
        Path file = temp.resolve(id + ".m4a");
        Process process = new ProcessBuilder(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=" + seconds,
            "-c:a",
            "aac",
            "-b:a",
            "96k",
            file.toString()
        )
            .redirectErrorStream(true)
            .start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());

        LibraryTrack track = new LibraryTrack();
        track.id = id;
        track.title = title;
        track.artist = "Test Author";
        track.album = "Test Book";
        track.baseName = "Test Author - " + title;
        track.durationMs = Math.round(seconds * 1000);
        track.recordedAtEpochMs = System.currentTimeMillis();
        track.status = "ready";
        track.files.put("m4a", file.getFileName().toString());
        // the library resolves files inside the library dir
        Files.copy(
            file,
            libraryDir.resolve(file.getFileName().toString()),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
        return track;
    }
}
