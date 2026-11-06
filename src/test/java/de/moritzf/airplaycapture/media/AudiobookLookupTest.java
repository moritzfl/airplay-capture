package de.moritzf.airplaycapture.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudiobookLookupTest {

    private static final String RESULTS = """
    {
      "resultCount": 2,
      "results": [
        {
          "wrapperType": "audiobook",
          "collectionName": "Harry Potter and the Philosopher's Stone (Unabridged)",
          "artistName": "J.K. Rowling, Stephen Fry"
        },
        {
          "wrapperType": "audiobook",
          "collectionName": "A Different Book",
          "artistName": "Someone Else"
        }
      ]
    }
    """;

    @Test
    void confirmsMatchingAudiobook() {
        AudiobookLookup.Verdict verdict = AudiobookLookup.parse(
            RESULTS,
            "Harry Potter and the Philosopher's Stone",
            "J.K. Rowling"
        );
        assertTrue(verdict.audiobook());
        assertEquals(
            "Harry Potter and the Philosopher's Stone (Unabridged)",
            verdict.matchedTitle()
        );
    }

    @Test
    void titleOnlyLookupIgnoresAPlaylistArtist() {
        AudiobookLookup.Verdict verdict = AudiobookLookup.parse(
            RESULTS,
            "Harry Potter and the Philosopher's Stone",
            "My Playlist 42",
            true
        );
        assertTrue(verdict.audiobook());
    }

    @Test
    void rejectsWrongAuthor() {
        AudiobookLookup.Verdict verdict = AudiobookLookup.parse(
            RESULTS,
            "Harry Potter and the Philosopher's Stone",
            "A Completely Different Author"
        );
        assertFalse(verdict.audiobook());
    }

    @Test
    void listsMatchingAppleEditionsWithArtworkAndYear() {
        String json = """
        {"resultCount":3,"results":[
          {"wrapperType":"audiobook","collectionId":123,
           "collectionName":"Harry Potter and the Philosopher's Stone",
           "artistName":"J.K. Rowling","releaseDate":"1997-06-26T07:00:00Z",
           "artworkUrl100":"https://example.test/cover/100x100bb.jpg"},
          {"wrapperType":"audiobook","collectionId":456,
           "collectionName":"Harry Potter and the Chamber of Secrets",
           "artistName":"J.K. Rowling","releaseDate":"1998-07-02T07:00:00Z",
           "artworkUrl100":"https://example.test/cover2/100x100bb.jpg"},
          {"wrapperType":"track","collectionId":789,
           "collectionName":"Not an audiobook","artistName":"Someone"}
        ]}
        """;

        var editions = AudiobookLookup.parseEditions(
            json,
            "Harry Potter and the Philosopher's Stone",
            "J.K. Rowling",
            false
        );

        assertEquals(1, editions.size());
        assertEquals("123", editions.get(0).id());
        assertEquals(1997, editions.get(0).year());
        assertTrue(editions.get(0).artworkUrl().contains("600x600bb"));
    }

    @Test
    void titleOnlyMusicBrainzLookupIgnoresArtist() {
        String json = """
        {"release-groups":[{"title":"Harry Potter and the Philosopher’s Stone",
         "secondary-types":["Audiobook"],
         "artist-credit":[{"name":"J. K. Rowling","artist":{"name":"J. K. Rowling"}}]}]}
        """;
        assertTrue(
            AudiobookLookup.parseMusicBrainz(
                json,
                "Harry Potter and the Philosopher's Stone",
                "Some Player",
                true
            ).audiobook()
        );
    }

    @Test
    void parsesMusicBrainzAudiobook() {
        String json = """
        {"release-groups":[{"title":"Harry Potter and the Philosopher’s Stone",
         "secondary-types":["Audiobook"],
         "artist-credit":[{"name":"J. K. Rowling","artist":{"name":"J. K. Rowling"}}]}]}
        """;
        AudiobookLookup.Verdict verdict = AudiobookLookup.parseMusicBrainz(
            json,
            "Harry Potter and the Philosopher's Stone",
            "J.K. Rowling"
        );
        assertTrue(verdict.audiobook());
        assertTrue(verdict.matchedTitle().contains("Harry Potter"));
    }

    @Test
    void ignoresMusicBrainzMusicRelease() {
        String json = """
        {"release-groups":[{"title":"Harry Potter and the Philosopher's Stone",
         "secondary-types":["Soundtrack"],
         "artist-credit":[{"name":"J.K. Rowling","artist":{"name":"J.K. Rowling"}}]}]}
        """;
        assertFalse(
            AudiobookLookup.parseMusicBrainz(
                json,
                "Harry Potter and the Philosopher's Stone",
                "J.K. Rowling"
            ).audiobook()
        );
    }

    @Test
    void normalizesAccentsAndPunctuation() {
        assertTrue(AudiobookLookup.matches("Hörbuch: Teil 1", "Horbuch Teil 1"));
        assertTrue(AudiobookLookup.matchesAuthor("J.K. Rowling", "J. K. Rowling"));
        assertFalse(AudiobookLookup.matches("Der Hobbit", "Something Else Entirely"));
    }
}
