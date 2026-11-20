package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.moritzf.airplaycapture.media.AudiobookAssembler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AudiobookAssemblerTest {

    @TempDir
    Path temp;

    @Test
    void joinsChaptersWithMarksAndTags() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        Path first = TestMedia.createAlacCaf(temp, 2.0);
        Path second = TestMedia.createAlacCaf(temp, 3.0);
        Path cover = TestMedia.createCover(temp);
        Path output = temp.resolve("book.m4b");

        new AudiobookAssembler("ffmpeg", 96).assemble(
            List.of(
                new AudiobookAssembler.Chapter(first, "Chapter One", 2000),
                new AudiobookAssembler.Chapter(second, "Chapter Two", 3000)
            ),
            cover,
            output,
            "Test Book",
            "Test Author"
        );

        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 10_000);
        String chapters = TestMedia.ffprobe(
            output,
            "-show_chapters",
            "-show_entries",
            "chapter=start_time,end_time:chapter_tags=title",
            "-of",
            "json"
        );
        assertTrue(chapters.contains("Chapter One"), chapters);
        assertTrue(chapters.contains("Chapter Two"), chapters);
        String tags = TestMedia.ffprobe(
            output,
            "-show_entries",
            "format=duration:format_tags=title,artist,album,genre",
            "-of",
            "default=nw=1"
        );
        assertTrue(tags.contains("Test Book"), tags);
        assertTrue(tags.contains("Test Author"), tags);
        assertTrue(tags.contains("Audiobook"), tags);
        double duration = Double.parseDouble(tags.replaceAll("(?s).*duration=([0-9.]+).*", "$1"));
        assertTrue(duration > 4.5 && duration < 5.5, tags);
    }

    @Test
    void writesMoreThan255NamedMarkersWithoutSplittingAudio() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        Path audio = TestMedia.createAlacCaf(temp, 5.0);
        Path output = temp.resolve("many-markers.m4b");
        var markers = IntStream.rangeClosed(1, 300)
            .mapToObj(i -> new AudiobookAssembler.Marker("Kapitel " + i + ": Mut & Herz #1", 10))
            .toList();

        new AudiobookAssembler("ffmpeg", 96).assemble(
            new AudiobookAssembler.Timeline(audio, 1_000, markers),
            null,
            output,
            "Test Book",
            "Test Author",
            null,
            null
        );

        var file = JsonMapper.builder()
            .build()
            .readTree(
                TestMedia.ffprobe(
                    output,
                    "-show_chapters",
                    "-show_entries",
                    "chapter=start_time,end_time:chapter_tags=title:format=duration",
                    "-of",
                    "json"
                )
            );
        var chapters = file.path("chapters");
        assertEquals(300, chapters.size());
        assertEquals(3.0, file.path("format").path("duration").asDouble(), 0.03);
        for (int i = 0; i < markers.size(); i++) {
            var chapter = chapters.get(i);
            assertEquals(markers.get(i).title(), chapter.path("tags").path("title").asString());
            assertEquals(i * 0.01, chapter.path("start_time").asDouble(), 0.001);
            assertEquals((i + 1) * 0.01, chapter.path("end_time").asDouble(), 0.001);
        }
    }

    @Test
    void stopsWhenTheEncoderProducesFarMoreAudioThanPlanned() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        // 90 s of audio declared as 5 s: a wrong input list must fail fast instead of encoding on
        // until a timeout, and must not leave a broken file behind.
        Path long90 = TestMedia.createAlacCaf(temp, 90.0);
        Path output = temp.resolve("overrun.m4b");

        IOException error = assertThrows(IOException.class, () ->
            new AudiobookAssembler("ffmpeg", 96).assemble(
                List.of(new AudiobookAssembler.Chapter(long90, "Only", 5000)),
                null,
                output,
                "Overrun",
                "Author"
            )
        );

        assertTrue(
            error.getMessage().contains("more audio") || error.getMessage().contains("of audio"),
            error.getMessage()
        );
        assertFalse(Files.exists(output), "partial output must be removed");
    }

    @Test
    void cancellingStopsTheEncodeAndRemovesThePartialFile() throws Exception {
        assumeTrue(TestMedia.ffmpegAvailable(), "ffmpeg required");
        Path source = TestMedia.createAlacCaf(temp, 120.0);
        Path output = temp.resolve("cancelled.m4b");
        AtomicBoolean cancel = new AtomicBoolean();

        long started = System.currentTimeMillis();
        assertThrows(CancellationException.class, () ->
            new AudiobookAssembler("ffmpeg", 96).assemble(
                List.of(new AudiobookAssembler.Chapter(source, "Only", 120_000)),
                null,
                output,
                "Cancelled",
                "Author",
                fraction -> cancel.set(true),
                cancel::get
            )
        );

        assertTrue(
            System.currentTimeMillis() - started < 15_000,
            "cancel must not wait for the encode"
        );
        assertFalse(Files.exists(output), "partial output must be removed");
    }
}
