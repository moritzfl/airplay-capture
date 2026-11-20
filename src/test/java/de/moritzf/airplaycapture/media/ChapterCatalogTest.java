package de.moritzf.airplaycapture.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChapterCatalogTest {

    @Test
    void parsesDeezerAlbumTracksAsChapters() throws Exception {
        String json = """
        {"data":[
          {"title":"Kapitel 01: Die Eiskönigin - Völlig Unverfroren","duration":90},
          {"title":"Kapitel 02: Die Eiskönigin - Völlig Unverfroren","duration":97}
        ]}
        """;

        List<ChapterCatalog.Chapter> chapters = ChapterCatalog.readDeezerTracks(2, index -> json);

        assertEquals(2, chapters.size());
        assertEquals("Kapitel 01: Die Eiskönigin - Völlig Unverfroren", chapters.get(0).title());
        assertEquals(90_000, chapters.get(0).durationMs());
        assertEquals(97_000, chapters.get(1).durationMs());
    }

    @Test
    void readsPastTheFirstHundredTracksInPlaybackOrder() throws Exception {
        List<Integer> offsets = new ArrayList<>();
        var chapters = ChapterCatalog.readDeezerTracks(171, index -> {
            offsets.add(index);
            return deezerPage(index, Math.min(index + 100, 171), 171);
        });

        assertEquals(List.of(0, 100), offsets);
        assertEquals(171, chapters.size());
        assertEquals(23_256_000, ChapterCatalog.totalMs(chapters));
        assertEquals("Kapitel 100: Heldin", chapters.get(99).title());
        assertEquals("Kapitel 101: Heldin", chapters.get(100).title());
        assertEquals("Kapitel 171: Heldin", chapters.getLast().title());
    }

    @Test
    void doesNotUsePartialOrRepeatedPagesAsAnEdition() {
        assertThrows(IllegalStateException.class, () ->
            ChapterCatalog.readDeezerTracks(171, index ->
                index == 0 ? deezerPage(0, 100, 171) : "{\"error\":{\"code\":4}}"
            )
        );
        assertThrows(IllegalStateException.class, () ->
            ChapterCatalog.readDeezerTracks(171, index -> deezerPage(0, 100, 171))
        );
    }

    private static String deezerPage(int from, int to, int total) {
        String data = IntStream.range(from + 1, to + 1)
            .mapToObj(number ->
                """
                {"id":%d,"title":"Kapitel %d: Heldin","duration":136}""".formatted(number, number)
            )
            .collect(Collectors.joining(","));
        return """
        {"data":[%s],"total":%d%s}
        """.formatted(data, total, to < total ? ",\"next\":\"next page\"" : "");
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
    void buildsAKeywordBagInsteadOfFieldRestrictedPhrases() {
        // Field restrictions lost real audiobooks: exact artist phrases fail on punctuation
        // variants ("George R.R. Martin" vs the indexed "George R. R. Martin") and on
        // narrator/playlist names in the artist field.
        assertEquals(
            "\"Die drei ??? und der Phantomsee\" \"Die drei ???\"",
            ChapterCatalog.musicBrainzQuery("Die drei ??? und der Phantomsee", "Die drei ???")
        );
        assertEquals("\"Der Hobbit\"", ChapterCatalog.musicBrainzQuery("Der Hobbit", null));
        assertEquals("\"Der Hobbit\"", ChapterCatalog.musicBrainzQuery("Der Hobbit", "  "));
        assertEquals(
            "\"Ein Buch\" \"Autor\"",
            ChapterCatalog.musicBrainzQuery("Ein \"Buch\"", "Autor")
        );
    }

    @Test
    void rejectsCatalogListsWithoutUsableDurations() {
        String json = """
        {"data":[
          {"title":"Kapitel 1","duration":0},
          {"title":"Kapitel 2","duration":90}
        ]}
        """;

        assertThrows(IllegalStateException.class, () ->
            ChapterCatalog.readDeezerTracks(2, index -> json)
        );
    }
}
