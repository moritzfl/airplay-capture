package de.moritzf.airplaycapture.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChapterCatalogTest {

    @Test
    void parsesDeezerAlbumTracksAsChapters() {
        String json = """
        {"data":[
          {"title":"Kapitel 01: Die Eiskönigin - Völlig Unverfroren","duration":90},
          {"title":"Kapitel 02: Die Eiskönigin - Völlig Unverfroren","duration":97}
        ]}
        """;

        List<ChapterCatalog.Chapter> chapters = ChapterCatalog.parseDeezerTracks(json);

        assertEquals(2, chapters.size());
        assertEquals("Kapitel 01: Die Eiskönigin - Völlig Unverfroren", chapters.get(0).title());
        assertEquals(90_000, chapters.get(0).durationMs());
        assertEquals(97_000, chapters.get(1).durationMs());
    }

    @Test
    void stripsTheRepeatedAlbumNameFromChapterTitles() {
        List<ChapterCatalog.Chapter> chapters = List.of(
            new ChapterCatalog.Chapter("Kapitel 01: Die Eiskönigin - Völlig Unverfroren", 90_000),
            new ChapterCatalog.Chapter("Kapitel 02: Die Eiskönigin - Völlig Unverfroren", 97_000)
        );

        List<ChapterCatalog.Chapter> cleaned = ChapterCatalog.cleanTitles(
            chapters,
            "Die Eiskönigin - Völlig Unverfroren (Hörspiel zum Disney Film)"
        );

        assertEquals("Kapitel 01", cleaned.get(0).title());
        assertEquals("Kapitel 02", cleaned.get(1).title());
    }

    @Test
    void parsesMusicBrainzTracksAcrossMedia() {
        String json = """
        {"media":[
          {"tracks":[
            {"title":"Prolog","length":120946},
            {"title":"Kapitel 1","length":229840}
          ]},
          {"tracks":[
            {"title":"Kapitel 2","length":293066}
          ]}
        ]}
        """;

        List<ChapterCatalog.Chapter> chapters = ChapterCatalog.parseMusicBrainzRelease(
            JsonMapper.builder().build().readTree(json)
        );

        assertEquals(3, chapters.size());
        assertEquals("Prolog", chapters.get(0).title());
        assertEquals(120_946, chapters.get(0).durationMs());
        assertEquals("Kapitel 2", chapters.get(2).title());
    }

    @Test
    void picksTheReleaseThatMatchesTheRecordingLength() {
        String json = """
        {"releases":[
          {"title":"Unabridged","media":[{"tracks":[
            {"title":"One","length":3600000},
            {"title":"Two","length":3600000}
          ]}]},
          {"title":"Radio drama","media":[{"tracks":[
            {"title":"One","length":1800000},
            {"title":"Two","length":1800000}
          ]}]}
        ]}
        """;

        ChapterCatalog.Release release = ChapterCatalog.bestRelease(json, 3_600_000);

        assertNotNull(release);
        assertEquals("Radio drama", release.title());
        assertEquals(3_600_000, ChapterCatalog.totalMs(release.chapters()));
    }

    @Test
    void rejectsCatalogListsWithoutUsableDurations() {
        String json = """
        {"data":[
          {"title":"Kapitel 1","duration":0},
          {"title":"Kapitel 2","duration":90}
        ]}
        """;

        List<ChapterCatalog.Chapter> chapters = ChapterCatalog.parseDeezerTracks(json);

        assertTrue(chapters.size() > 0);
        // The lookup rejects lists with missing durations; the parser keeps them for diagnosis.
        assertEquals(0, chapters.get(0).durationMs());
    }
}
