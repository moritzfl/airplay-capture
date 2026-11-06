package de.moritzf.airplaycapture.media;

import de.moritzf.airplaycapture.Log;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confirms audiobook candidates against keyless public catalogs: Apple's iTunes Search API has an
 * explicit audiobook media type, MusicBrainz has an "Audiobook" release-group type.
 *
 * <p>Lookups run asynchronously and are cached, so recording never waits for the network.
 */
public final class AudiobookLookup {

    public record Verdict(boolean audiobook, String matchedTitle, String matchedAuthor) {}

    /** One Apple Books edition. Apple has no chapter data, only metadata and cover art. */
    public record Edition(
        String id,
        String title,
        String author,
        Integer year,
        String artworkUrl
    ) {}

    public record Options(boolean apple, boolean musicBrainz, String country, String contact) {
        boolean any() {
            return apple || musicBrainz;
        }

        String signature() {
            return (
                (apple ? "a" : "") +
                (musicBrainz ? "m" : "") +
                "|" +
                (country == null ? "" : country) +
                "|" +
                (contact == null ? "" : contact)
            );
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Duration POSITIVE_TTL = Duration.ofDays(7);
    private static final Duration NEGATIVE_TTL = Duration.ofHours(12);
    private static final long MIN_REQUEST_INTERVAL_MS = 1100;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audiobook-lookup");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private volatile long lastRequestAt;

    private record Cached(Verdict verdict, long expiresAt) {}

    public void request(String album, String artist, Options options) {
        request(null, album, artist, options);
    }

    /**
     * Looks up a book by album, or by title when the sender provides no album. Title-only lookups
     * ignore the artist, because audiobook players often send a playlist name instead of the author.
     */
    public void request(String title, String album, String artist, Options options) {
        boolean titleOnly = album == null || album.isBlank();
        String subject = titleOnly ? title : album;
        if (subject == null || subject.isBlank() || options == null || !options.any()) {
            return;
        }
        String key = lookupKey(subject, artist, options, titleOnly);
        if (cached(key).isPresent() || inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        worker.submit(() -> {
            try {
                Verdict verdict = query(subject, titleOnly ? "" : artist, options, titleOnly);
                long ttl = (verdict.audiobook() ? POSITIVE_TTL : NEGATIVE_TTL).toMillis();
                cache.put(key, new Cached(verdict, System.currentTimeMillis() + ttl));
                if (verdict.audiobook()) {
                    Log.info(
                        "lookup",
                        "audiobook confirmed: '" +
                            verdict.matchedTitle() +
                            "' / " +
                            verdict.matchedAuthor()
                    );
                } else {
                    Log.info(
                        "lookup",
                        "no audiobook match for '" +
                            subject +
                            "'" +
                            (titleOnly ? "" : " / " + artist)
                    );
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.warn("lookup", "audiobook lookup failed: " + e.getMessage());
                cache.put(
                    key,
                    new Cached(
                        new Verdict(false, null, null),
                        System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()
                    )
                );
            } finally {
                inFlight.remove(key);
            }
        });
    }

    public Optional<Verdict> cached(String album, String artist, Options options) {
        return cached(null, album, artist, options);
    }

    public Optional<Verdict> cached(String title, String album, String artist, Options options) {
        boolean titleOnly = album == null || album.isBlank();
        String subject = titleOnly ? title : album;
        if (subject == null || subject.isBlank() || options == null || !options.any()) {
            return Optional.empty();
        }
        return cached(lookupKey(subject, artist, options, titleOnly));
    }

    /** Synchronous lookup for explicit user actions (for example the audiobook dialog). */
    public Optional<Verdict> lookup(String title, String album, String artist, Options options) {
        boolean titleOnly = album == null || album.isBlank();
        String subject = titleOnly ? title : album;
        if (subject == null || subject.isBlank() || options == null || !options.any()) {
            return Optional.empty();
        }
        try {
            return Optional.of(query(subject, titleOnly ? "" : artist, options, titleOnly));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            Log.warn("lookup", "manual audiobook lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns every matching Apple Books edition, newest relevance order preserved. */
    public List<Edition> search(String title, String album, String artist, Options options) {
        boolean titleOnly = album == null || album.isBlank();
        String subject = titleOnly ? title : album;
        if (subject == null || subject.isBlank() || options == null || !options.apple()) {
            return List.of();
        }
        try {
            return appleEditions(subject, titleOnly ? "" : artist, options, titleOnly);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            Log.warn("lookup", "Apple Books search failed: " + e.getMessage());
            return List.of();
        }
    }

    private List<Edition> appleEditions(
        String album,
        String artist,
        Options options,
        boolean titleOnly
    ) throws Exception {
        throttle();
        String term =
            titleOnly || artist == null || artist.isBlank() ? album : album + " " + artist;
        String cc =
            options.country() == null || options.country().isBlank()
                ? "US"
                : options.country().trim().toUpperCase(Locale.ROOT);
        String url =
            "https://itunes.apple.com/search?media=audiobook&entity=audiobook&limit=10" +
            "&country=" +
            encode(cc) +
            "&term=" +
            encode(term);
        String body = get(url, "AirPlayCapture/1.0");
        return parseEditions(body, album, artist, titleOnly);
    }

    private static String lookupKey(
        String subject,
        String artist,
        Options options,
        boolean titleOnly
    ) {
        return key(subject, titleOnly ? "" : artist, options) + (titleOnly ? "|title" : "");
    }

    private Optional<Verdict> cached(String key) {
        Cached entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.verdict());
    }

    private Verdict query(String subject, String artist, Options options, boolean titleOnly)
        throws Exception {
        if (options.apple()) {
            Verdict verdict = apple(subject, artist, options, titleOnly);
            if (verdict.audiobook()) {
                return verdict;
            }
        }
        if (options.musicBrainz()) {
            Verdict verdict = musicBrainz(subject, artist, options, titleOnly);
            if (verdict.audiobook()) {
                return verdict;
            }
        }
        return new Verdict(false, null, null);
    }

    private Verdict apple(String album, String artist, Options options, boolean titleOnly)
        throws Exception {
        throttle();
        String term =
            titleOnly || artist == null || artist.isBlank() ? album : album + " " + artist;
        String cc =
            options.country() == null || options.country().isBlank()
                ? "US"
                : options.country().trim().toUpperCase(Locale.ROOT);
        String url =
            "https://itunes.apple.com/search?media=audiobook&entity=audiobook&limit=10" +
            "&country=" +
            encode(cc) +
            "&term=" +
            encode(term);
        String body = get(url, "AirPlayCapture/1.0");
        return parse(body, album, artist, titleOnly);
    }

    private Verdict musicBrainz(String album, String artist, Options options, boolean titleOnly)
        throws Exception {
        throttle();
        String query = "releasegroup:\"" + album.replace("\"", "") + "\"";
        if (!titleOnly && artist != null && !artist.isBlank()) {
            query += " AND artist:\"" + artist.replace("\"", "") + "\"";
        }
        String url =
            "https://musicbrainz.org/ws/2/release-group/?query=" +
            encode(query) +
            "&fmt=json&limit=5";
        String contact =
            options.contact() == null || options.contact().isBlank()
                ? "self-hosted"
                : options.contact().trim();
        String body = get(url, "AirPlayCapture/1.0 (" + contact + ")");
        return parseMusicBrainz(body, album, artist, titleOnly);
    }

    private String get(String url, String userAgent) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(7))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "HTTP " + response.statusCode() + " from " + URI.create(url).getHost()
            );
        }
        return response.body();
    }

    private void throttle() throws InterruptedException {
        long wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- parsing (also unit tested)

    static Verdict parse(String json, String album, String artist) {
        return parse(json, album, artist, false);
    }

    static Verdict parse(String json, String expected, String artist, boolean titleOnly) {
        JsonNode results = JSON.readTree(json).path("results");
        if (!results.isArray()) {
            return new Verdict(false, null, null);
        }
        for (JsonNode item : results) {
            if (!item.isObject()) {
                continue;
            }
            String wrapper = item.path("wrapperType").asString("");
            if (!wrapper.isEmpty() && !"audiobook".equals(wrapper)) {
                continue;
            }
            String title = item.path("collectionName").asString("");
            String author = item.path("artistName").asString("");
            if (matches(expected, title) && (titleOnly || matchesAuthor(artist, author))) {
                return new Verdict(true, title, author);
            }
        }
        return new Verdict(false, null, null);
    }

    static List<Edition> parseEditions(
        String json,
        String expected,
        String artist,
        boolean titleOnly
    ) {
        JsonNode results = JSON.readTree(json).path("results");
        List<Edition> editions = new ArrayList<>();
        if (!results.isArray()) {
            return editions;
        }
        for (JsonNode item : results) {
            if (!item.isObject()) {
                continue;
            }
            String wrapper = item.path("wrapperType").asString("");
            if (!wrapper.isEmpty() && !"audiobook".equals(wrapper)) {
                continue;
            }
            String title = item.path("collectionName").asString("");
            String author = item.path("artistName").asString("");
            if (!matches(expected, title) || (!titleOnly && !matchesAuthor(artist, author))) {
                continue;
            }
            String id = item.path("collectionId").asString("");
            if (id.isBlank()) {
                continue;
            }
            editions.add(
                new Edition(
                    id,
                    title,
                    author,
                    year(item.path("releaseDate").asString("")),
                    largeArtwork(item.path("artworkUrl100").asString(""))
                )
            );
        }
        return editions;
    }

    private static String largeArtwork(String url) {
        return url == null
            ? ""
            : url.replace("/100x100bb.", "/600x600bb.").replace("/100x100.", "/600x600.");
    }

    private static Integer year(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        try {
            int value = Integer.parseInt(releaseDate.substring(0, 4));
            return value > 1000 && value < 3000 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Verdict parseMusicBrainz(String json, String album, String artist) {
        return parseMusicBrainz(json, album, artist, false);
    }

    static Verdict parseMusicBrainz(
        String json,
        String expected,
        String artist,
        boolean titleOnly
    ) {
        JsonNode groups = JSON.readTree(json).path("release-groups");
        if (!groups.isArray()) {
            return new Verdict(false, null, null);
        }
        for (JsonNode group : groups) {
            String title = group.path("title").asString("");
            boolean audiobookType = false;
            JsonNode types = group.path("secondary-types");
            if (types.isArray()) {
                for (JsonNode type : types) {
                    String value = normalize(type.asString(""));
                    if (
                        value.equals("audiobook") ||
                        value.equals("spokenword") ||
                        value.equals("audio drama") ||
                        value.equals("audio book")
                    ) {
                        audiobookType = true;
                        break;
                    }
                }
            }
            if (!audiobookType || !matches(expected, title)) {
                continue;
            }
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
            if (titleOnly || matchesAuthor(artist, author.toString())) {
                return new Verdict(true, title, author.toString());
            }
        }
        return new Verdict(false, null, null);
    }

    static boolean matches(String query, String candidate) {
        String a = normalize(query);
        String b = normalize(candidate);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        return similarity(a, b) >= 0.8;
    }

    static boolean matchesAuthor(String query, String candidate) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String a = normalize(query);
        String b = normalize(candidate);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        return similarity(a, b) >= 0.6;
    }

    private static String key(String album, String artist, Options options) {
        return options.signature() + "|" + normalize(album) + "|" + normalize(artist);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(
            value.toLowerCase(Locale.ROOT),
            Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");
        return decomposed.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1;
        }
        return 1.0 - (double) distance(a, b) / max;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    public void close() {
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
