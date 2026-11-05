package de.moritzf.airplaycapture.library;

import java.util.LinkedHashMap;
import java.util.Map;

/** One exported recording as shown in the web UI. */
public final class LibraryTrack {

    public String id;
    public String title;
    public String artist;
    public String album;
    public String genre;
    public Integer trackNumber;
    public Integer year;
    public long durationMs;
    /** Sender-reported track length, when it provided progress information. */
    public long expectedDurationMs;
    /** True when playback stopped before the sender-reported duration. */
    public boolean incomplete;
    public long recordedAtEpochMs;
    public String baseName;
    public Map<String, String> files = new LinkedHashMap<>();
    public long sizeBytes;
    public boolean cover;
    /** Perceptual dHash of the cover image; keeps different albums/books with the same title apart. */
    public String coverHash;
    public String source;
    public int gaps;
    /** Total audio packets that could not be recovered across all gaps. */
    public int gapPackets;
    /** Lost packets written as silence, keeping the recording's length; 0 for older entries. */
    public int filledPackets;
    /** Gaps that retransmits closed in time, and the longest wait for one. */
    public int recoveredGaps;
    public long maxRecoveryMs;
    public Integer mediaKind;
    /** True if this file is one chapter of a book that is also assembled as .m4b. */
    public boolean audiobookChapter;
    /** Shared id for an assembled .m4b and the chapter recordings it was built from. */
    public String bookId;
    /** Number of chapters in an assembled audiobook. */
    public Integer chapterCount;
    /** When an assembled audiobook was written; 0 for recordings and older entries. */
    public long createdAtEpochMs;
    /** Catalog that filled missing tags, e.g. "apple"; null if untouched. */
    public String metadataSource;
    /** "processing", "ready" or "error". */
    public String status = "processing";
    public String error;

    public String displayName() {
        String name = baseName;
        if (name == null || name.isBlank()) {
            name = title == null || title.isBlank() ? "Untitled" : title;
        }
        return name;
    }
}
