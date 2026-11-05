package de.moritzf.airplaycapture.library;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Groups recordings that belong together: one album (album + all its tracks), or one audiobook
 * (assembled .m4b plus the chapter recordings it was built from).
 *
 * <p>Duplicate recordings of the same track are never merged. They stay separate rows and are
 * ranked so the UI and ZIP downloads can prefer the best take.
 */
public final class LibraryGroups {

    /** dHash bits that may differ for the same cover after resizing or recompression. */
    private static final int COVER_HAMMING_DISTANCE = 6;
    /** Summed RGB distance across the 2x2 colour grid; same cover stays well below this. */
    private static final int COVER_COLOR_DISTANCE = 240;

    /** An album/book row with the recordings that belong to it. */
    public record Group(String key, String title, String artist, List<LibraryTrack> tracks) {

        public boolean expandable() {
            return tracks.size() > 1;
        }

        public boolean isAudiobook() {
            return tracks.stream().anyMatch(track -> track.audiobookChapter || track.chapterCount != null);
        }

        public boolean hasM4b() {
            return m4b().isPresent();
        }

        /** The current audiobook: the newest assembled .m4b, so a rebuild replaces the download. */
        public Optional<LibraryTrack> m4b() {
            LibraryTrack newest = null;
            for (LibraryTrack track : tracks) {
                if (track.chapterCount == null || !track.files.containsKey("m4b")) {
                    continue;
                }
                if (newest == null || track.createdAtEpochMs > newest.createdAtEpochMs) {
                    newest = track;
                }
            }
            return Optional.ofNullable(newest);
        }

        /** True for an assembled .m4b that a later rebuild superseded. */
        public boolean isEarlierVersion(LibraryTrack track) {
            return track.chapterCount != null
                    && m4b().map(current -> current != track).orElse(false);
        }

        public long durationMs() {
            Optional<LibraryTrack> m4b = m4b();
            if (m4b.isPresent()) {
                return m4b.get().durationMs;
            }
            return bestTakes().stream().mapToLong(track -> track.durationMs).sum();
        }

        public long sizeBytes() {
            return tracks.stream().mapToLong(track -> track.sizeBytes).sum();
        }

        public long recordedAtEpochMs() {
            return tracks.stream().mapToLong(track -> track.recordedAtEpochMs).max().orElse(0);
        }

        public List<LibraryTrack> bestTakes() {
            return LibraryGroups.bestTakes(tracks);
        }

        /**
         * Formats worth offering as a group download. For ZIPs only the best takes count, so a
         * format that exists solely on a discarded duplicate does not produce an empty archive.
         */
        public Set<String> formats(boolean bestOnly) {
            Set<String> formats = new HashSet<>();
            for (LibraryTrack track : bestOnly ? bestTakes() : tracks) {
                formats.addAll(track.files.keySet());
            }
            return formats;
        }

        /** Number of recordings that are duplicates of another recording in this group. */
        public int duplicateCount() {
            int duplicates = 0;
            for (List<LibraryTrack> takes : takes(tracks).values()) {
                duplicates += takes.size() - 1;
            }
            return duplicates;
        }
    }

    /** Position of a recording among duplicate takes, for badges. */
    public record Take(int index, int total, boolean best) {
    }

    private LibraryGroups() {
    }

    public static List<Group> groups(List<LibraryTrack> tracks) {
        Map<String, List<LibraryTrack>> buckets = new LinkedHashMap<>();
        for (LibraryTrack track : tracks) {
            String key = groupKey(track);
            if (key == null) {
                key = "s" + track.id;
            }
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(track);
        }

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<LibraryTrack>> entry : buckets.entrySet()) {
            for (List<LibraryTrack> members : clusters(entry.getValue())) {
                String key = clusterKey(entry.getKey(), members);
                if (members.size() == 1 && !isAlbumGroup(key) && !isBookGroup(key)) {
                    LibraryTrack track = members.get(0);
                    groups.add(new Group(key, track.title, track.artist, List.of(track)));
                    continue;
                }
                members.sort(Comparator
                        .comparingInt((LibraryTrack track) -> track.trackNumber == null
                                ? Integer.MAX_VALUE : track.trackNumber)
                        .thenComparingLong(track -> track.recordedAtEpochMs));
                LibraryTrack anchor = anchor(members);
                String title = !isBlank(anchor.album) ? anchor.album : displayTitle(anchor);
                String artist = displayArtist(members);
                groups.add(new Group(key, title, artist, List.copyOf(members)));
            }
        }
        groups.sort(Comparator.comparingLong(Group::recordedAtEpochMs).reversed());
        return groups;
    }

    /**
     * Splits one title bucket by cover art. Recordings without a cover stay together when there is
     * no ambiguity; with several different covers they cannot be assigned safely and stay separate.
     * Explicit book ids always win, because the assembler already knows those belong together.
     */
    private static List<List<LibraryTrack>> clusters(List<LibraryTrack> members) {
        if (members.stream().anyMatch(track -> isBookPart(track) && !isBlank(track.bookId))) {
            return List.of(members);
        }
        List<Cluster> clusters = new ArrayList<>();
        List<LibraryTrack> blank = null;
        for (LibraryTrack track : members) {
            String hash = track.coverHash;
            if (isBlank(hash)) {
                if (blank == null) {
                    if (clusters.size() == 1) {
                        blank = clusters.get(0).tracks;
                    } else {
                        blank = new ArrayList<>();
                        clusters.add(new Cluster("", blank));
                    }
                }
                blank.add(track);
                continue;
            }
            Cluster best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Cluster cluster : clusters) {
                if (isBlank(cluster.hash)) {
                    continue;
                }
                int distance = hamming(hash, cluster.hash);
                if (distance <= COVER_HAMMING_DISTANCE
                        && colorDistance(hash, cluster.hash) <= COVER_COLOR_DISTANCE
                        && distance < bestDistance) {
                    best = cluster;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                clusters.add(new Cluster(hash, new ArrayList<>(List.of(track))));
            } else {
                best.tracks.add(track);
            }
        }
        return clusters.stream().map(cluster -> cluster.tracks).toList();
    }

    private static String clusterKey(String baseKey, List<LibraryTrack> members) {
        String hash = members.stream()
                .map(track -> track.coverHash)
                .filter(LibraryGroups::isNotBlank)
                .min(String::compareTo)
                .orElse("");
        return hash.isBlank() ? baseKey + "-nocover" : baseKey + "-" + hash;
    }

    private static int hamming(String first, String second) {
        try {
            long a = Long.parseUnsignedLong(first.substring(0, 16), 16);
            long b = Long.parseUnsignedLong(second.substring(0, 16), 16);
            return Long.bitCount(a ^ b);
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int colorDistance(String first, String second) {
        if (first.length() < 40 || second.length() < 40) {
            return 0; // legacy hash without colour data: do not block the dHash match
        }
        int distance = 0;
        for (int i = 16; i < 40; i += 6) {
            distance += Math.abs(component(first, i) - component(second, i));
            distance += Math.abs(component(first, i + 2) - component(second, i + 2));
            distance += Math.abs(component(first, i + 4) - component(second, i + 4));
        }
        return distance;
    }

    private static int component(String value, int offset) {
        return Integer.parseInt(value.substring(offset, offset + 2), 16);
    }

    private static final class Cluster {
        final String hash;
        final List<LibraryTrack> tracks;

        Cluster(String hash, List<LibraryTrack> tracks) {
            this.hash = hash;
            this.tracks = tracks;
        }
    }

    public static Optional<Group> find(List<LibraryTrack> tracks, String key) {
        return groups(tracks).stream().filter(group -> group.key().equals(key)).findFirst();
    }

    /** True for an assembled book or one of its chapter files. */
    public static boolean isBookPart(LibraryTrack track) {
        return (track.bookId != null && !track.bookId.isBlank())
                || track.audiobookChapter
                || track.chapterCount != null;
    }

    /**
     * Groups duplicates inside one album/book. The .m4b itself is not a take; chapter recordings
     * are only compared with other chapter recordings, music tracks only with music tracks.
     */
    public static Map<String, List<LibraryTrack>> takes(List<LibraryTrack> tracks) {
        Map<String, List<LibraryTrack>> takes = new LinkedHashMap<>();
        for (LibraryTrack track : tracks) {
            String key = takeKey(track);
            if (key != null) {
                takes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(track);
            }
        }
        takes.values().removeIf(list -> list.size() < 2);
        for (List<LibraryTrack> list : takes.values()) {
            list.sort(LibraryGroups::compareQuality);
        }
        return takes;
    }

    public static Take take(List<LibraryTrack> tracks, LibraryTrack track) {
        for (List<LibraryTrack> list : takes(tracks).values()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == track) {
                    return new Take(i + 1, list.size(), i == 0);
                }
            }
        }
        return null;
    }

    /** One recording per duplicate set; keeps the input order. */
    public static List<LibraryTrack> bestTakes(List<LibraryTrack> tracks) {
        Map<String, LibraryTrack> best = new HashMap<>();
        for (LibraryTrack track : tracks) {
            String key = takeKey(track);
            if (key == null) {
                continue;
            }
            LibraryTrack current = best.get(key);
            if (current == null || compareQuality(track, current) < 0) {
                best.put(key, track);
            }
        }
        List<LibraryTrack> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LibraryTrack track : tracks) {
            String key = takeKey(track);
            if (key == null) {
                result.add(track);
            } else if (Objects.equals(best.get(key), track) && seen.add(key)) {
                result.add(track);
            }
        }
        return result;
    }

    /**
     * Best first: complete before incomplete, fewer lost packets, fewer gaps, longer recording,
     * newer as the final tiebreaker.
     */
    public static int compareQuality(LibraryTrack a, LibraryTrack b) {
        int result = Integer.compare(statusRank(a.status), statusRank(b.status));
        if (result != 0) {
            return result;
        }
        result = Boolean.compare(a.incomplete, b.incomplete);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.gapPackets, b.gapPackets);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.gaps, b.gaps);
        if (result != 0) {
            return result;
        }
        result = Long.compare(b.durationMs, a.durationMs);
        if (result != 0) {
            return result;
        }
        return Long.compare(b.recordedAtEpochMs, a.recordedAtEpochMs);
    }

    private static int statusRank(String status) {
        if ("ready".equals(status)) {
            return 0;
        }
        if ("processing".equals(status) || status == null) {
            return 1;
        }
        return 2;
    }

    private static String groupKey(LibraryTrack track) {
        String album = norm(track.album);
        if (isBookPart(track)) {
            String bookId = norm(track.bookId);
            if (!bookId.isBlank()) {
                return "b" + hash("book:" + bookId);
            }
            String base = album.isBlank() ? norm(track.title) : album;
            return base.isBlank() ? null : "b" + hash("book:" + base);
        }
        if (!album.isBlank()) {
            return "a" + hash("album:" + album);
        }
        return null;
    }

    private static boolean isAlbumGroup(String key) {
        return key.startsWith("a");
    }

    private static boolean isBookGroup(String key) {
        return key.startsWith("b");
    }

    private static LibraryTrack anchor(List<LibraryTrack> members) {
        return members.stream()
                .filter(track -> track.chapterCount != null)
                .findFirst()
                .orElse(members.get(0));
    }

    private static String displayTitle(LibraryTrack anchor) {
        if (!isBlank(anchor.title)) {
            return anchor.title;
        }
        return isBlank(anchor.baseName) ? "Untitled" : anchor.baseName;
    }

    private static String displayArtist(List<LibraryTrack> members) {
        Optional<LibraryTrack> m4b = members.stream()
                .filter(track -> track.chapterCount != null && isNotBlank(track.artist))
                .findFirst();
        if (m4b.isPresent()) {
            return m4b.get().artist;
        }
        List<String> names = members.stream()
                .map(track -> track.artist)
                .filter(LibraryGroups::isNotBlank)
                .distinct()
                .toList();
        if (names.size() == 1) {
            return names.get(0);
        }
        return names.isEmpty() ? "" : "Various artists";
    }

    private static String takeKey(LibraryTrack track) {
        if (track.chapterCount != null) {
            return null; // assembled .m4b, not a duplicate of its chapters
        }
        String title = norm(track.title);
        if (title.isBlank()) {
            return null;
        }
        if (track.audiobookChapter) {
            // Chapters can legitimately share the book title when an app plays them as individual
            // tracks. Track numbers are the strongest identity; otherwise only treat them as
            // takes when their length is close. Distinct chapters must not be dropped.
            if (track.trackNumber != null) {
                return "chapter:" + title + "|" + norm(track.artist) + "|#" + track.trackNumber;
            }
            if (track.durationMs <= 0) {
                return null;
            }
            long minutes = Math.round(track.durationMs / 60_000.0);
            return "chapter:" + title + "|" + norm(track.artist) + "|" + minutes;
        }
        return "track:" + title + "|" + norm(track.artist);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
