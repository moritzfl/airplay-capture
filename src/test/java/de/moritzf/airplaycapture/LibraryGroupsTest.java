package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import java.util.List;
import org.junit.jupiter.api.Test;

class LibraryGroupsTest {

    @Test
    void groupsTracksOfOneAlbumIntoOneExpandableEntry() {
        LibraryTrack first = track("t1", "Song One", "Artist A", "Same Album", 1_000);
        LibraryTrack second = track("t2", "Song Two", "Artist B", "Same Album", 2_000);

        List<LibraryGroups.Group> groups = LibraryGroups.groups(List.of(second, first));

        assertEquals(1, groups.size());
        LibraryGroups.Group group = groups.get(0);
        assertEquals("Same Album", group.title());
        assertEquals("Various artists", group.artist());
        assertEquals(List.of(first, second), group.tracks());
        assertTrue(group.expandable());
        assertEquals(2, group.tracks().size());
        assertFalse(group.isAudiobook());
    }

    @Test
    void ordersIndividualChapterRecordingsByTrackNumber() {
        LibraryTrack second = track("t2", "Chapter Two", "Narrator", "Book", 2_000);
        second.trackNumber = 2;
        second.audiobookChapter = true;
        LibraryTrack first = track("t1", "Chapter One", "Narrator", "Book", 1_000);
        first.trackNumber = 1;
        first.audiobookChapter = true;

        LibraryGroups.Group group = LibraryGroups.groups(List.of(second, first)).get(0);

        assertEquals(List.of(first, second), group.tracks());
        assertTrue(group.isAudiobook());
    }

    @Test
    void keepsDuplicateTakesSeparateAndPrefersTheCleanerOne() {
        LibraryTrack worse = track("t1", "Song", "Artist", "Album", 1_000);
        worse.gaps = 4;
        worse.gapPackets = 40;
        worse.files.put("m4a", "worse.m4a");
        LibraryTrack better = track("t2", "Song", "Artist", "Album", 2_000);
        better.files.put("m4a", "better.m4a");
        LibraryTrack longerButGappy = track("t3", "Song", "Artist", "Album", 3_000);
        longerButGappy.gaps = 9;
        longerButGappy.gapPackets = 90;
        longerButGappy.durationMs = 999_000;
        longerButGappy.files.put("m4a", "gappy.m4a");

        LibraryGroups.Group group = LibraryGroups.groups(
            List.of(longerButGappy, worse, better)
        ).get(0);

        assertEquals(3, group.tracks().size(), "all takes stay visible");
        assertEquals(2, group.duplicateCount());
        assertEquals(List.of(better), group.bestTakes());
        LibraryGroups.Take take = LibraryGroups.take(group.tracks(), better);
        assertTrue(take.best());
        assertEquals(1, take.index());
        assertEquals(2, LibraryGroups.take(group.tracks(), worse).index());
    }

    @Test
    void prefersCompleteOverLongerIncompleteTake() {
        LibraryTrack incomplete = track("t1", "Song", "Artist", "Album", 1_000);
        incomplete.durationMs = 600_000;
        incomplete.incomplete = true;
        LibraryTrack complete = track("t2", "Song", "Artist", "Album", 2_000);
        complete.durationMs = 300_000;

        assertEquals(List.of(complete), LibraryGroups.bestTakes(List.of(incomplete, complete)));
    }

    @Test
    void doesNotSeeTheAssembledM4bAsADuplicateOfItsChapter() {
        LibraryTrack chapter = track("c1", "Book", "Author", "", 1_000);
        chapter.audiobookChapter = true;
        chapter.bookId = "b1";
        LibraryTrack book = track("m4b", "Book", "Author", "Book", 1_000);
        book.chapterCount = 4;
        book.bookId = "b1";
        book.files.put("m4b", "book.m4b");

        LibraryGroups.Group group = LibraryGroups.groups(List.of(book, chapter)).get(0);

        assertEquals(2, group.tracks().size());
        assertEquals(0, group.duplicateCount());
        assertTrue(group.hasM4b());
        assertTrue(group.isAudiobook());
    }

    @Test
    void groupsLegacyBookWhenChaptersHaveNoAlbum() {
        LibraryTrack chapterOne = track("c1", "Book", "Narrator One", "", 1_000);
        chapterOne.audiobookChapter = true;
        LibraryTrack chapterTwo = track("c2", "Book", "Narrator Two", "", 2_000);
        chapterTwo.audiobookChapter = true;
        LibraryTrack book = track("m4b", "Book", "Narrator One", "Book", 1_000);
        book.chapterCount = 2;
        book.files.put("m4b", "book.m4b");

        List<LibraryGroups.Group> groups = LibraryGroups.groups(
            List.of(book, chapterOne, chapterTwo)
        );

        assertEquals(1, groups.size());
        assertEquals(3, groups.get(0).tracks().size());
    }

    @Test
    void separatesBooksWithTheSameTitleButDifferentCovers() {
        LibraryTrack first = track("c1", "Die Eiskönigin", "Völlig Unverfroren", "", 1_000);
        first.audiobookChapter = true;
        first.coverHash = "aaaaaaaaaaaaaaaa";
        LibraryTrack second = track("c2", "Die Eiskönigin", "Teil 2", "", 2_000);
        second.audiobookChapter = true;
        second.coverHash = "5555555555555555";

        List<LibraryGroups.Group> groups = LibraryGroups.groups(List.of(first, second));

        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).tracks().size());
        assertEquals(1, groups.get(1).tracks().size());
    }

    @Test
    void keepsBookPartsTogetherWhenTheCoverMatches() {
        LibraryTrack first = track("c1", "Die Eiskönigin", "Völlig Unverfroren", "", 1_000);
        first.audiobookChapter = true;
        first.coverHash = "aaaaaaaaaaaaaaaa";
        LibraryTrack second = track("c2", "Die Eiskönigin", "Teil 2", "", 2_000);
        second.audiobookChapter = true;
        second.coverHash = "aaaaaaaaaaaaaaaa";

        List<LibraryGroups.Group> groups = LibraryGroups.groups(List.of(first, second));

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).tracks().size());
    }

    @Test
    void separatesAlbumsWithTheSameNameButDifferentCovers() {
        LibraryTrack first = track("t1", "Song One", "Artist A", "Same Album", 1_000);
        first.coverHash = "aaaaaaaaaaaaaaaa";
        LibraryTrack second = track("t2", "Song Two", "Artist B", "Same Album", 2_000);
        second.coverHash = "5555555555555555";

        List<LibraryGroups.Group> groups = LibraryGroups.groups(List.of(first, second));

        assertEquals(2, groups.size());
    }

    @Test
    void separatesCoversWithTheSameLayoutButDifferentColors() {
        LibraryTrack first = track("c1", "Die Eiskönigin", "Völlig Unverfroren", "", 1_000);
        first.audiobookChapter = true;
        first.coverHash = "aaaaaaaaaaaaaaaa" + "ff0000".repeat(4);
        LibraryTrack second = track("c2", "Die Eiskönigin", "Teil 2", "", 2_000);
        second.audiobookChapter = true;
        second.coverHash = "aaaaaaaaaaaaaaaa" + "0000ff".repeat(4);

        List<LibraryGroups.Group> groups = LibraryGroups.groups(List.of(first, second));

        assertEquals(2, groups.size());
    }

    @Test
    void leavesLooseSinglesAsSingleRows() {
        LibraryTrack first = track("t1", "Song", "Artist", "", 1_000);
        LibraryTrack second = track("t2", "Other", "Artist", "", 2_000);

        List<LibraryGroups.Group> groups = LibraryGroups.groups(List.of(second, first));

        assertEquals(2, groups.size());
        assertFalse(groups.get(0).expandable());
    }

    @Test
    void keepsChaptersWithSameTitleButDifferentLengthsAsDistinctContent() {
        LibraryTrack first = track("c1", "Book", "Narrator", "Book", 1_000);
        first.audiobookChapter = true;
        first.durationMs = 10 * 60 * 1000;
        LibraryTrack second = track("c2", "Book", "Narrator", "Book", 2_000);
        second.audiobookChapter = true;
        second.durationMs = 20 * 60 * 1000;

        LibraryGroups.Group group = LibraryGroups.groups(List.of(first, second)).get(0);

        assertEquals(0, group.duplicateCount());
        assertEquals(2, group.bestTakes().size());
    }

    @Test
    void treatsSimilarLengthChaptersWithTheSameTitleAsTakes() {
        LibraryTrack first = track("c1", "Book", "Narrator", "Book", 1_000);
        first.audiobookChapter = true;
        first.durationMs = 10 * 60 * 1000;
        first.gaps = 3;
        LibraryTrack second = track("c2", "Book", "Narrator", "Book", 2_000);
        second.audiobookChapter = true;
        second.durationMs = 10 * 60 * 1000 + 20_000;

        LibraryGroups.Group group = LibraryGroups.groups(List.of(first, second)).get(0);

        assertEquals(1, group.duplicateCount());
        assertEquals(1, group.bestTakes().size());
        assertEquals(second, group.bestTakes().get(0));
    }

    @Test
    void trackNumbersKeepChaptersWithTheSameTitleAndLengthSeparate() {
        LibraryTrack first = track("c1", "Book", "Narrator", "Book", 1_000);
        first.audiobookChapter = true;
        first.trackNumber = 1;
        LibraryTrack second = track("c2", "Book", "Narrator", "Book", 2_000);
        second.audiobookChapter = true;
        second.trackNumber = 2;

        LibraryGroups.Group group = LibraryGroups.groups(List.of(first, second)).get(0);

        assertEquals(0, group.duplicateCount());
        assertEquals(2, group.bestTakes().size());
    }

    private static LibraryTrack track(
        String id,
        String title,
        String artist,
        String album,
        long recordedAt
    ) {
        LibraryTrack track = new LibraryTrack();
        track.id = id;
        track.title = title;
        track.artist = artist;
        track.album = album;
        track.baseName = artist + " - " + title;
        track.durationMs = 240_000;
        track.recordedAtEpochMs = recordedAt;
        track.status = "ready";
        return track;
    }
}
