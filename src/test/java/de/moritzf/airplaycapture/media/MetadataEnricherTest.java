package de.moritzf.airplaycapture.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataEnricherTest {

    private static final String ALBUM_SEARCH = """
            {"results":[
              {"wrapperType":"collection","collectionType":"Album","collectionId":12345,
               "collectionName":"Test Album","artistName":"Test Artist",
               "primaryGenreName":"Pop","releaseDate":"2019-05-01T07:00:00Z",
               "artworkUrl100":"https://example.test/100x100bb.jpg"}
            ]}
            """;

    private static final String TRACK_LOOKUP = """
            {"results":[
              {"wrapperType":"collection","collectionId":12345,"collectionName":"Test Album"},
              {"wrapperType":"track","trackName":"First Song","trackNumber":1,
               "primaryGenreName":"Pop","releaseDate":"2019-05-01T07:00:00Z",
               "artworkUrl100":"https://example.test/100x100bb.jpg"},
              {"wrapperType":"track","trackName":"Second Song","trackNumber":2,
               "primaryGenreName":"Pop","releaseDate":"2019-05-01T07:00:00Z",
               "artworkUrl100":"https://example.test/100x100bb.jpg"}
            ]}
            """;

    private static final String SONG_SEARCH = """
            {"results":[
              {"wrapperType":"track","trackName":"Test Song","artistName":"Test Artist",
               "collectionName":"Test Album","primaryGenreName":"Pop",
               "releaseDate":"2020-01-01T07:00:00Z","trackNumber":3,
               "artworkUrl100":"https://example.test/100x100bb.jpg"}
            ]}
            """;

    @Test
    void parsesAlbumSearch() {
        var album = MetadataEnricher.parseAlbumSearch(ALBUM_SEARCH, "Test Album", "Test Artist");
        assertTrue(album.isPresent());
        assertEquals("12345", album.get().collectionId());
        assertEquals("Test Album", album.get().title());
        assertEquals("Pop", album.get().genre());
        assertEquals(2019, album.get().year());
    }

    @Test
    void rejectsWrongAlbumArtist() {
        assertFalse(MetadataEnricher.parseAlbumSearch(ALBUM_SEARCH, "Test Album", "Other Artist").isPresent());
    }

    @Test
    void matchesTracksByNumberOrTitle() {
        var album = MetadataEnricher.parseAlbumSearch(ALBUM_SEARCH, "Test Album", "Test Artist").orElseThrow();
        var detailed = MetadataEnricher.parseTrackLookup(TRACK_LOOKUP, album);
        assertEquals(2, detailed.tracks().size());
        assertEquals("Second Song",
                MetadataEnricher.matchTrack(detailed.tracks(), "ignored", 2).orElseThrow().title());
        assertEquals("First Song",
                MetadataEnricher.matchTrack(detailed.tracks(), "First Song", null).orElseThrow().title());
    }

    @Test
    void parsesSongSearchForMissingAlbum() {
        var result = MetadataEnricher.parseSongSearch(SONG_SEARCH, "Test Song", "Test Artist");
        assertTrue(result.isPresent());
        assertEquals("Test Album", result.get().album());
        assertEquals("Pop", result.get().genre());
        assertEquals(2020, result.get().year());
        assertEquals(3, result.get().trackNumber());
    }

    @Test
    void rejectsSongByWrongArtist() {
        assertFalse(MetadataEnricher.parseSongSearch(SONG_SEARCH, "Test Song", "Other Artist").isPresent());
    }

    private static final String DZ_ALBUM_SEARCH = """
            {"data":[{"id":6575789,"title":"Test Album","artist":{"name":"Test Artist"},
              "cover_xl":"https://dz.example/1000x1000.jpg"}]}
            """;

    private static final String DZ_ALBUM = """
            {"id":6575789,"title":"Test Album","release_date":"2019-05-17",
             "genre_id":113,"cover_xl":"https://dz.example/1000x1000.jpg"}
            """;

    private static final String DZ_TRACKS = """
            {"data":[{"title":"First Song","track_position":1,"disk_number":1},
                     {"title":"Second Song","track_position":2,"disk_number":1}]}
            """;

    private static final String DZ_TRACK_SEARCH = """
            {"data":[{"title":"Test Song","artist":{"name":"Test Artist"},
              "album":{"id":6575789,"title":"Test Album",
                       "cover_xl":"https://dz.example/1000x1000.jpg"}}]}
            """;

    @Test
    void parsesDeezerAlbumSearchAndTracks() {
        var base = MetadataEnricher.parseDeezerAlbumSearch(DZ_ALBUM_SEARCH, "Test Album", "Test Artist")
                .orElseThrow();
        assertEquals("6575789", base.collectionId());
        var album = MetadataEnricher.parseDeezerAlbum(DZ_ALBUM, "Dance", base);
        assertEquals(2019, album.year());
        var tracks = MetadataEnricher.parseDeezerTracks(DZ_TRACKS);
        assertEquals(2, tracks.size());
        assertEquals("Second Song",
                MetadataEnricher.matchTrack(tracks, "ignored", 2).orElseThrow().title());
        assertFalse(MetadataEnricher.parseDeezerAlbumSearch(DZ_ALBUM_SEARCH, "Test Album", "Other").isPresent());
    }

    @Test
    void parsesDeezerTrackSearch() {
        var base = MetadataEnricher.parseDeezerTrackSearch(DZ_TRACK_SEARCH, "Test Song", "Test Artist")
                .orElseThrow();
        assertEquals("6575789", base.collectionId());
        assertEquals("Test Album", base.title());
        assertFalse(MetadataEnricher.parseDeezerTrackSearch(DZ_TRACK_SEARCH, "Test Song", "Other").isPresent());
    }

    @Test
    void matchTrackReturnsEmptyForUnknownTitle() {
        var album = MetadataEnricher.parseAlbumSearch(ALBUM_SEARCH, "Test Album", "Test Artist").orElseThrow();
        var detailed = MetadataEnricher.parseTrackLookup(TRACK_LOOKUP, album);
        assertTrue(MetadataEnricher.matchTrack(detailed.tracks(), "Unknown", null).isEmpty());
        assertTrue(MetadataEnricher.matchTrack(List.of(), "First Song", 1).isEmpty());
    }
}
