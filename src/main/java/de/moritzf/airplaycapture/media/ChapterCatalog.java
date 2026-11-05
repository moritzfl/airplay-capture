package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.Log;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches chapter lists (names and durations) for an audiobook from keyless catalogs.
 *
 * <p>Apple's iTunes API only exposes the audiobook as one item, so it cannot provide chapters.
 * Deezer albums often contain one track per chapter, and MusicBrainz releases expose their track
 * list. Both are matched by title, artist and the expected total duration; the caller aligns the
 * catalog durations to the actual recording and snaps the cuts into pauses.
 */
public class ChapterCatalog {

    public record Chapter(String title, long durationMs) {
    }

    public record Result(String id, String source, String album, String artist, Integer year,
                         String coverUrl, List<Chapter> chapters, double score) {
        /** Convenience constructor for callers that only care about the chapter list. */
        public Result(String source, String album, String artist, List<Chapter> chapters) {
            this(null, source, album, artist, null, null, chapters, 0);
        }

        public long totalMs() {
            return chapters.stream().mapToLong(Chapter::durationMs).sum();
        }

        public boolean hasChapters() {
            return chapters != null && chapters.size() >= 2;
        }
    }

    public record Options(boolean deezer, boolean musicBrainz, String contact) {
        boolean any() {
            return deezer || musicBrainz;
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Duration POSITIVE_TTL = Duration.ofDays(7);
    private static final Duration NEGATIVE_TTL = Duration.ofHours(12);
    private static final long DEEZER_INTERVAL_MS = 250;
    private static final long MUSICBRAINZ_INTERVAL_MS = 1_100;
    private static final int MAX_CANDIDATES = 3;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedSearch> searchCache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private long lastDeezerRequestAt;
    private long lastMusicBrainzRequestAt;

    private record Cached(Result result, long expiresAt) {
    }

    private record CachedSearch(List<Result> results, long expiresAt) {
    }

    /**
     * Looks up a chapter list. {@code expectedDurationMs} is the recorded length; it is used to pick
     * the edition that matches the recording instead of an unabridged or abridged sibling.
     */
    public Optional<Result> lookup(String title, String album, String artist, long expectedDurationMs,
                                   Options options) {
        String subject = subject(title, album);
        if (subject == null || subject.isBlank() || options == null || !options.any()) {
            return Optional.empty();
        }
        String key = key(subject, artist, expectedDurationMs, options);
        Cached cached = cache.get(key);
        if (cached != null) {
            if (cached.expiresAt() > System.currentTimeMillis()) {
                return Optional.ofNullable(cached.result());
            }
            cache.remove(key);
        }
        try {
            Result result = query(subject, artist, expectedDurationMs, options);
            long ttl = (result != null ? POSITIVE_TTL : NEGATIVE_TTL).toMillis();
            cache.put(key, new Cached(result, System.currentTimeMillis() + ttl));
            if (result != null) {
                Log.info("lookup", "chapter list from " + result.source() + ": '"
                        + result.album() + "' (" + result.chapters().size() + " chapters, "
                        + result.totalMs() / 60000 + " min)");
            } else {
                Log.info("lookup", "no catalog chapter list for '" + subject + "'");
            }
            return Optional.ofNullable(result);
        } catch (Exception e) {
            Log.warn("lookup", "chapter list lookup failed for '" + subject + "': " + e.getMessage());
            cache.put(key, new Cached(null, System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()));
            return Optional.empty();
        }
    }

    /** Returns all matching editions, best first, for the edition picker. */
    public List<Result> search(String title, String album, String artist, long expectedDurationMs,
                               Options options) {
        String subject = subject(title, album);
        if (subject == null || subject.isBlank() || options == null || !options.any()) {
            return List.of();
        }
        String key = "search|" + key(subject, artist, expectedDurationMs, options);
        CachedSearch cached = searchCache.get(key);
        if (cached != null) {
            if (cached.expiresAt() > System.currentTimeMillis()) {
                return cached.results();
            }
            searchCache.remove(key);
        }
        List<Result> results = new ArrayList<>();
        if (options.deezer()) {
            try {
                results.addAll(deezer(subject, artist, expectedDurationMs));
            } catch (Exception e) {
                Log.warn("lookup", "Deezer chapter search failed: " + e.getMessage());
            }
        }
        if (options.musicBrainz()) {
            try {
                results.addAll(musicBrainz(subject, artist, expectedDurationMs, options.contact()));
            } catch (Exception e) {
                Log.warn("lookup", "MusicBrainz chapter search failed: " + e.getMessage());
            }
        }
        results = dedupe(results);
        results.sort(Comparator
                .comparingDouble((Result result) -> deviation(result, expectedDurationMs))
                .thenComparing(Comparator.comparingDouble(Result::score).reversed()));
        if (results.size() > 8) {
            results = new ArrayList<>(results.subList(0, 8));
        }
        long ttl = (results.isEmpty() ? NEGATIVE_TTL : POSITIVE_TTL).toMillis();
        searchCache.put(key, new CachedSearch(List.copyOf(results),
                System.currentTimeMillis() + ttl));
        return List.copyOf(results);
    }

    private Result query(String subject, String artist, long expectedDurationMs, Options options) {
        List<Result> deezer = List.of();
        if (options.deezer()) {
            try {
                deezer = deezer(subject, artist, expectedDurationMs);
            } catch (Exception e) {
                Log.warn("lookup", "Deezer chapter lookup failed: " + e.getMessage());
            }
        }
        Result bestDeezer = best(deezer);
        if (bestDeezer != null && fits(bestDeezer, expectedDurationMs, 0.05)) {
            return bestDeezer;
        }
        List<Result> musicBrainz = List.of();
        if (options.musicBrainz()) {
            try {
                musicBrainz = musicBrainz(subject, artist, expectedDurationMs, options.contact());
            } catch (Exception e) {
                Log.warn("lookup", "MusicBrainz chapter lookup failed: " + e.getMessage());
            }
        }
        Result bestMusicBrainz = best(musicBrainz);
        if (bestDeezer == null) {
            return bestMusicBrainz;
        }
        if (bestMusicBrainz == null) {
            return bestDeezer;
        }
        return deviation(bestMusicBrainz, expectedDurationMs)
                < deviation(bestDeezer, expectedDurationMs) ? bestMusicBrainz : bestDeezer;
    }

    private static Result best(List<Result> results) {
        return results.stream().max(Comparator.comparingDouble(Result::score)).orElse(null);
    }

    private static List<Result> dedupe(List<Result> results) {
        Map<String, Result> unique = new java.util.LinkedHashMap<>();
        for (Result result : results) {
            String key = result.source() + "|" + (result.id() == null ? result.album() : result.id());
            unique.putIfAbsent(key, result);
        }
        return new ArrayList<>(unique.values());
    }

    // ---------------------------------------------------------------- Deezer

    private List<Result> deezer(String subject, String artist, long expectedDurationMs)
            throws Exception {
        String term = artist == null || artist.isBlank() ? subject : subject + " " + artist;
        String body = get("https://api.deezer.com/search/album?limit=10&q=" + encode(term),
                "AirPlayCapture/1.0", true);
        List<Candidate> candidates = deezerCandidates(body, subject, artist);
        if (candidates.isEmpty() && artist != null && !artist.isBlank()) {
            body = get("https://api.deezer.com/search/album?limit=10&q=" + encode(subject),
                    "AirPlayCapture/1.0", true);
            candidates = deezerCandidates(body, subject, null);
        }
        List<Result> results = new ArrayList<>();
        for (Candidate candidate : candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()))) {
            try {
                String albumJson = get("https://api.deezer.com/album/" + encode(candidate.id()),
                        "AirPlayCapture/1.0", true);
                String tracksJson = get("https://api.deezer.com/album/" + encode(candidate.id())
                        + "/tracks?limit=100", "AirPlayCapture/1.0", true);
                List<Chapter> chapters = cleanTitles(parseDeezerTracks(tracksJson), candidate.title());
                if (!usable(chapters)) {
                    continue;
                }
                long total = totalMs(chapters);
                double score = candidate.similarity() * 0.35
                        + durationScore(expectedDurationMs, total) * 0.65;
                JsonNode album = safeRead(albumJson);
                String albumTitle = album.path("title").asString(candidate.title());
                String albumArtist = album.path("artist").path("name").asString(candidate.artist());
                results.add(new Result(candidate.id(), "Deezer", albumTitle, albumArtist,
                        year(album.path("release_date").asString("")),
                        album.path("cover_big").asString(""), chapters, score));
            } catch (Exception e) {
                Log.warn("lookup", "Deezer album " + candidate.id() + " failed: " + e.getMessage());
            }
        }
        return results;
    }

    private static List<Candidate> deezerCandidates(String json, String subject, String artist) {
        JsonNode data = safeRead(json).path("data");
        List<Candidate> candidates = new ArrayList<>();
        if (!data.isArray()) {
            return candidates;
        }
        for (JsonNode item : data) {
            String title = item.path("title").asString("");
            String candidateArtist = item.path("artist").path("name").asString("");
            if (!MetadataEnricher.matches(subject, title) || !likelyChapters(title)) {
                continue;
            }
            boolean artistMatches = MetadataEnricher.matchesAuthor(artist, candidateArtist);
            boolean titleNamesTheSubject = MetadataEnricher.normalize(title)
                    .startsWith(MetadataEnricher.normalize(subject));
            if (!artistMatches && !titleNamesTheSubject) {
                continue;
            }
            String id = id(item);
            if (id.isBlank()) {
                continue;
            }
            candidates.add(new Candidate(id, title, candidateArtist,
                    MetadataEnricher.similarity(MetadataEnricher.normalize(subject),
                            MetadataEnricher.normalize(title))));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        return candidates;
    }

    static List<Chapter> parseDeezerTracks(String json) {
        JsonNode data = safeRead(json).path("data");
        List<Chapter> chapters = new ArrayList<>();
        if (!data.isArray()) {
            return chapters;
        }
        for (JsonNode item : data) {
            String title = item.path("title").asString("");
            long duration = item.path("duration").isNumber()
                    ? item.path("duration").asLong() * 1000 : 0;
            if (!title.isBlank()) {
                chapters.add(new Chapter(title, duration));
            }
        }
        return chapters;
    }

    // ---------------------------------------------------------------- MusicBrainz

    private List<Result> musicBrainz(String subject, String artist, long expectedDurationMs,
                                     String contactSetting) throws Exception {
        String query = "releasegroup:\"" + subject.replace("\"", "") + "\"";
        if (artist != null && !artist.isBlank()) {
            query += " AND artist:\"" + artist.replace("\"", "") + "\"";
        }
        String contact = contactSetting == null || contactSetting.isBlank()
                ? "self-hosted" : contactSetting.trim();
        String body = get("https://musicbrainz.org/ws/2/release-group/?query=" + encode(query)
                + "&fmt=json&limit=5", "AirPlayCapture/1.0 (" + contact + ")", false);
        List<Group> groups = musicBrainzGroups(body, subject, artist);
        if (groups.isEmpty() && artist != null && !artist.isBlank()) {
            String titleOnly = "releasegroup:\"" + subject.replace("\"", "") + "\"";
            body = get("https://musicbrainz.org/ws/2/release-group/?query=" + encode(titleOnly)
                    + "&fmt=json&limit=5", "AirPlayCapture/1.0 (" + contact + ")", false);
            groups = musicBrainzGroups(body, subject, null);
        }
        List<Result> results = new ArrayList<>();
        for (Group group : groups.subList(0, Math.min(2, groups.size()))) {
            try {
                String releases = get("https://musicbrainz.org/ws/2/release?release-group="
                        + encode(group.id()) + "&inc=recordings&fmt=json&limit=10",
                        "AirPlayCapture/1.0 (" + contact + ")", false);
                Release release = bestRelease(releases, expectedDurationMs);
                if (release == null) {
                    continue;
                }
                double score = group.similarity() * 0.35
                        + durationScore(expectedDurationMs, totalMs(release.chapters())) * 0.65;
                results.add(new Result(group.id(), "MusicBrainz", group.title(), group.artist(),
                        group.year(), "https://coverartarchive.org/release-group/"
                        + group.id() + "/front-250", release.chapters(), score));
            } catch (Exception e) {
                Log.warn("lookup", "MusicBrainz release group " + group.id()
                        + " failed: " + e.getMessage());
            }
        }
        return results;
    }

    private static List<Group> musicBrainzGroups(String json, String subject, String artist) {
        JsonNode groups = safeRead(json).path("release-groups");
        List<Group> candidates = new ArrayList<>();
        if (!groups.isArray()) {
            return candidates;
        }
        for (JsonNode group : groups) {
            String title = group.path("title").asString("");
            if (!MetadataEnricher.matches(subject, title) || !audiobookType(group)) {
                continue;
            }
            String credit = artistCredit(group);
            boolean artistMatches = MetadataEnricher.matchesAuthor(artist, credit);
            boolean titleNamesTheSubject = MetadataEnricher.normalize(title)
                    .startsWith(MetadataEnricher.normalize(subject));
            if (!artistMatches && !titleNamesTheSubject) {
                continue;
            }
            String id = group.path("id").asString("");
            if (id.isBlank()) {
                continue;
            }
            candidates.add(new Group(id, title, credit,
                    year(group.path("first-release-date").asString("")),
                    MetadataEnricher.similarity(MetadataEnricher.normalize(subject),
                            MetadataEnricher.normalize(title))));
        }
        candidates.sort(Comparator.comparingDouble(Group::similarity).reversed());
        return candidates;
    }

    private static boolean audiobookType(JsonNode group) {
        String primary = MetadataEnricher.normalize(group.path("primary-type").asString(""));
        if (primary.equals("audiobook") || primary.equals("audio drama")) {
            return true;
        }
        JsonNode types = group.path("secondary-types");
        if (!types.isArray()) {
            return false;
        }
        for (JsonNode type : types) {
            String value = MetadataEnricher.normalize(type.asString(""));
            if (value.equals("audiobook") || value.equals("spokenword")
                    || value.equals("audio drama") || value.equals("audio book")) {
                return true;
            }
        }
        return false;
    }

    private static String artistCredit(JsonNode group) {
        StringBuilder author = new StringBuilder();
        JsonNode credits = group.path("artist-credit");
        if (credits.isArray()) {
            for (JsonNode credit : credits) {
                String name = credit.path("name").asString("");
                if (name.isBlank()) {
                    name = credit.path("artist").path("name").asString("");
                }
                if (!name.isBlank()) {
                    if (author.length() > 0) {
                        author.append(", ");
                    }
                    author.append(name);
                }
            }
        }
        return author.toString();
    }

    static Release bestRelease(String json, long expectedDurationMs) {
        JsonNode releases = safeRead(json).path("releases");
        Release best = null;
        double bestScore = -1;
        if (!releases.isArray()) {
            return null;
        }
        for (JsonNode release : releases) {
            List<Chapter> chapters = parseMusicBrainzRelease(release);
            if (!usable(chapters)) {
                continue;
            }
            double score = durationScore(expectedDurationMs, totalMs(chapters));
            if (score > bestScore) {
                bestScore = score;
                best = new Release(release.path("title").asString(""), chapters);
            }
        }
        return best;
    }

    static List<Chapter> parseMusicBrainzRelease(JsonNode release) {
        List<Chapter> chapters = new ArrayList<>();
        JsonNode media = release.path("media");
        if (!media.isArray()) {
            return chapters;
        }
        for (JsonNode medium : media) {
            JsonNode tracks = medium.path("tracks");
            if (!tracks.isArray()) {
                continue;
            }
            for (JsonNode track : tracks) {
                String title = track.path("title").asString("");
                long length = track.path("length").isNumber() ? track.path("length").asLong() : 0;
                if (title.isBlank() || length <= 0) {
                    // An incomplete track list would shift every later chapter; reject the release.
                    return List.of();
                }
                chapters.add(new Chapter(title, length));
            }
        }
        return chapters;
    }

    // ---------------------------------------------------------------- helpers

    static List<Chapter> cleanTitles(List<Chapter> chapters, String album) {
        if (album == null || album.isBlank()) {
            return chapters;
        }
        String normalizedAlbum = MetadataEnricher.normalize(album);
        List<Chapter> cleaned = new ArrayList<>();
        for (Chapter chapter : chapters) {
            String title = chapter.title();
            int colon = title.lastIndexOf(": ");
            String colonSuffix = colon > 0 ? MetadataEnricher.normalize(title.substring(colon + 2)) : "";
            if (!colonSuffix.isEmpty() && normalizedAlbum.contains(colonSuffix)) {
                title = title.substring(0, colon).trim();
            } else {
                int dash = title.lastIndexOf(" - ");
                String dashSuffix = dash > 0 ? MetadataEnricher.normalize(title.substring(dash + 3)) : "";
                if (!dashSuffix.isEmpty() && normalizedAlbum.contains(dashSuffix)) {
                    title = title.substring(0, dash).trim();
                }
            }
            cleaned.add(new Chapter(title, chapter.durationMs()));
        }
        return cleaned;
    }

    private static boolean likelyChapters(String title) {
        String normalized = MetadataEnricher.normalize(title);
        return !normalized.contains("soundtrack")
                && !normalized.contains("karaoke")
                && !normalized.contains("filmmusik");
    }

    private static String subject(String title, String album) {
        if (album == null || album.isBlank()) {
            return title;
        }
        if (title == null || title.isBlank()) {
            return album;
        }
        return title.length() > album.length() ? title : album;
    }

    private static boolean usable(List<Chapter> chapters) {
        if (chapters.size() < 2) {
            return false;
        }
        for (Chapter chapter : chapters) {
            if (chapter.durationMs() <= 0) {
                return false;
            }
        }
        return true;
    }

    static long totalMs(List<Chapter> chapters) {
        return chapters.stream().mapToLong(Chapter::durationMs).sum();
    }

    private static Integer year(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        try {
            int value = Integer.parseInt(date.substring(0, 4));
            return value > 1000 && value < 3000 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean fits(Result result, long expectedDurationMs, double tolerance) {
        return expectedDurationMs <= 0 || deviation(result, expectedDurationMs) <= tolerance;
    }

    private static double deviation(Result result, long expectedDurationMs) {
        long total = result.totalMs();
        if (expectedDurationMs <= 0 || total <= 0) {
            return 1;
        }
        return Math.abs(expectedDurationMs - total) / (double) expectedDurationMs;
    }

    private static double durationScore(long expectedDurationMs, long actualDurationMs) {
        if (expectedDurationMs <= 0 || actualDurationMs <= 0) {
            return 0.5;
        }
        double ratio = expectedDurationMs / (double) actualDurationMs;
        double deviation = Math.abs(Math.log(ratio));
        return 1.0 / (1.0 + deviation * 4.0);
    }

    private String get(String url, String userAgent, boolean deezer) throws Exception {
        throttle(deezer);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(7))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + " from " + URI.create(url).getHost());
        }
        return response.body();
    }

    private synchronized void throttle(boolean deezer) throws InterruptedException {
        long interval = deezer ? DEEZER_INTERVAL_MS : MUSICBRAINZ_INTERVAL_MS;
        long last = deezer ? lastDeezerRequestAt : lastMusicBrainzRequestAt;
        long wait = interval - (System.currentTimeMillis() - last);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        if (deezer) {
            lastDeezerRequestAt = System.currentTimeMillis();
        } else {
            lastMusicBrainzRequestAt = System.currentTimeMillis();
        }
    }

    private static JsonNode safeRead(String json) {
        return JSON.readTree(json.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " "));
    }

    private static String id(JsonNode node) {
        JsonNode id = node.path("id");
        if (id.isNumber()) {
            return String.valueOf(id.asLong());
        }
        return id.asString("");
    }

    private static String key(String subject, String artist, long expectedDurationMs,
                              Options options) {
        return (options.deezer() ? "d" : "") + (options.musicBrainz() ? "m" : "")
                + "|" + MetadataEnricher.normalize(subject)
                + "|" + MetadataEnricher.normalize(artist)
                + "|" + Math.max(0, expectedDurationMs / 60_000);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Candidate(String id, String title, String artist, double similarity) {
    }

    private record Group(String id, String title, String artist, Integer year, double similarity) {
    }

    record Release(String title, List<Chapter> chapters) {
    }
}
