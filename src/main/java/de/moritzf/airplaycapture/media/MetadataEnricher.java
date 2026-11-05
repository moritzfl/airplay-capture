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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fills gaps in sender metadata from keyless catalogs (Apple first, Deezer as fallback). Only used
 * when a field is empty, and only with a strict title/artist/album match. Sender values are never
 * overwritten.
 */
public final class MetadataEnricher {

    public record Result(String album, String genre, Integer year, Integer trackNumber, String artworkUrl) {
    }

    record AlbumInfo(String collectionId, String title, String genre, Integer year,
                     String artworkUrl, List<TrackInfo> tracks) {
    }

    record TrackInfo(String title, Integer trackNumber, String artworkUrl) {
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final long MIN_REQUEST_INTERVAL_MS = 1100;
    private static final Duration POSITIVE_TTL = Duration.ofDays(7);
    private static final Duration NEGATIVE_TTL = Duration.ofHours(12);

    private final String country;
    private final boolean deezerEnabled;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, CachedAlbums> albumCache = new ConcurrentHashMap<>();
    private final Map<String, CachedTracks> trackCache = new ConcurrentHashMap<>();
    private final Map<String, CachedAlbums> deezerAlbumCache = new ConcurrentHashMap<>();
    private final Map<String, CachedAlbums> deezerTrackCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> deezerGenres = new ConcurrentHashMap<>();
    private volatile long lastRequestAt;

    private record CachedAlbums(Optional<AlbumInfo> info, long expiresAt) {
    }

    private record CachedTracks(Optional<Result> result, long expiresAt) {
    }

    public MetadataEnricher(String country, boolean deezerEnabled) {
        this.country = country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT);
        this.deezerEnabled = deezerEnabled;
    }

    public Optional<Result> enrich(String title, String artist, String album, Integer trackNumber) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        Optional<Result> apple = album != null && !album.isBlank()
                ? appleAlbum(album, artist, title, trackNumber)
                : appleSong(title, artist);
        if (apple.isPresent() || !deezerEnabled) {
            return apple;
        }
        return deezerEnrich(title, artist, album, trackNumber);
    }

    private Optional<Result> appleAlbum(String album, String artist, String title, Integer trackNumber) {
        Optional<AlbumInfo> info = albumInfo(album, artist);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        AlbumInfo value = info.get();
        Optional<TrackInfo> match = matchTrack(value.tracks(), title, trackNumber);
        String artwork = match.map(TrackInfo::artworkUrl).filter(url -> !url.isBlank())
                .orElse(value.artworkUrl());
        Integer number = trackNumber != null ? trackNumber
                : match.map(TrackInfo::trackNumber).orElse(null);
        return Optional.of(new Result(value.title(), value.genre(), value.year(), number, artwork));
    }

    private Optional<Result> appleSong(String title, String artist) {
        return songSearch(title, artist);
    }

    // ---------------------------------------------------------------- Deezer fallback

    private Optional<Result> deezerEnrich(String title, String artist, String album, Integer trackNumber) {
        Optional<AlbumInfo> info = album != null && !album.isBlank()
                ? deezerAlbumInfo(album, artist)
                : deezerTrackInfo(title, artist);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        AlbumInfo value = info.get();
        Optional<TrackInfo> match = matchTrack(value.tracks(), title, trackNumber);
        String artwork = match.map(TrackInfo::artworkUrl).filter(url -> !url.isBlank())
                .orElse(value.artworkUrl());
        Integer number = trackNumber != null ? trackNumber
                : match.map(TrackInfo::trackNumber).orElse(null);
        return Optional.of(new Result(value.title(), value.genre(), value.year(), number, artwork));
    }

    private Optional<AlbumInfo> deezerAlbumInfo(String album, String artist) {
        String key = "dz|album|" + normalize(album) + "|" + normalize(artist);
        CachedAlbums cached = deezerAlbumCache.get(key);
        if (cached != null) {
            if (cached.expiresAt() > System.currentTimeMillis()) {
                return cached.info();
            }
            deezerAlbumCache.remove(key);
        }
        try {
            String term = artist == null || artist.isBlank() ? album : album + " " + artist;
            String body = get("https://api.deezer.com/search/album?limit=5&q=" + encode(term));
            Optional<AlbumInfo> base = parseDeezerAlbumSearch(body, album, artist);
            if (base.isEmpty()) {
                deezerAlbumCache.put(key, new CachedAlbums(Optional.empty(), expiresAt(false)));
                return Optional.empty();
            }
            AlbumInfo detail = deezerDetails(base.get());
            deezerAlbumCache.put(key, new CachedAlbums(Optional.of(detail), expiresAt(true)));
            return Optional.of(detail);
        } catch (Exception e) {
            Log.warn("lookup", "Deezer album lookup failed for '" + album + "': " + e.getMessage());
            deezerAlbumCache.put(key, new CachedAlbums(Optional.empty(),
                    System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()));
            return Optional.empty();
        }
    }

    private Optional<AlbumInfo> deezerTrackInfo(String title, String artist) {
        String key = "dz|track|" + normalize(title) + "|" + normalize(artist);
        CachedAlbums cached = deezerTrackCache.get(key);
        if (cached != null) {
            if (cached.expiresAt() > System.currentTimeMillis()) {
                return cached.info();
            }
            deezerTrackCache.remove(key);
        }
        try {
            String term = artist == null || artist.isBlank() ? title : title + " " + artist;
            String body = get("https://api.deezer.com/search/track?limit=10&q=" + encode(term));
            Optional<AlbumInfo> base = parseDeezerTrackSearch(body, title, artist);
            if (base.isEmpty()) {
                deezerTrackCache.put(key, new CachedAlbums(Optional.empty(), expiresAt(false)));
                return Optional.empty();
            }
            AlbumInfo detail = deezerDetails(base.get());
            deezerTrackCache.put(key, new CachedAlbums(Optional.of(detail), expiresAt(true)));
            return Optional.of(detail);
        } catch (Exception e) {
            Log.warn("lookup", "Deezer track lookup failed for '" + title + "': " + e.getMessage());
            deezerTrackCache.put(key, new CachedAlbums(Optional.empty(),
                    System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()));
            return Optional.empty();
        }
    }

    private AlbumInfo deezerDetails(AlbumInfo base) throws Exception {
        String albumJson = get("https://api.deezer.com/album/" + encode(base.collectionId()));
        JsonNode album = safeRead(albumJson);
        Integer genreId = album.path("genre_id").isNumber() ? album.path("genre_id").asInt() : null;
        String genre = deezerGenre(genreId);
        AlbumInfo withAlbum = parseDeezerAlbum(albumJson, genre, base);
        String tracksJson = get("https://api.deezer.com/album/" + encode(base.collectionId())
                + "/tracks?limit=100");
        return new AlbumInfo(withAlbum.collectionId(), withAlbum.title(), withAlbum.genre(),
                withAlbum.year(), withAlbum.artworkUrl(), parseDeezerTracks(tracksJson));
    }

    private String deezerGenre(Integer id) throws Exception {
        if (id == null) {
            return "";
        }
        String cached = deezerGenres.get(id);
        if (cached != null) {
            return cached;
        }
        String json = get("https://api.deezer.com/genre/" + id);
        String name = safeRead(json).path("name").asString("");
        deezerGenres.put(id, name);
        return name;
    }

    private Optional<AlbumInfo> albumInfo(String album, String artist) {
        String key = "album|" + normalize(album) + "|" + normalize(artist) + "|" + country;
        CachedAlbums cached = albumCache.get(key);
        if (cached != null) {
            if (cached.expiresAt() > System.currentTimeMillis()) {
                return cached.info();
            }
            albumCache.remove(key);
        }
        try {
            String term = artist == null || artist.isBlank() ? album : album + " " + artist;
            String body = get("https://itunes.apple.com/search?media=music&entity=album&limit=5"
                    + "&country=" + encode(country) + "&term=" + encode(term));
            Optional<AlbumInfo> info = parseAlbumSearch(body, album, artist);
            if (info.isPresent()) {
                AlbumInfo detail = lookupTracks(info.get());
                albumCache.put(key, new CachedAlbums(Optional.of(detail), expiresAt(true)));
                return Optional.of(detail);
            }
            albumCache.put(key, new CachedAlbums(Optional.empty(), expiresAt(false)));
            return Optional.empty();
        } catch (Exception e) {
            Log.warn("lookup", "album lookup failed for '" + album + "': " + e.getMessage());
            albumCache.put(key, new CachedAlbums(Optional.empty(),
                    System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()));
            return Optional.empty();
        }
    }

    private AlbumInfo lookupTracks(AlbumInfo info) throws Exception {
        String body = get("https://itunes.apple.com/lookup?entity=song&country=" + encode(country)
                + "&id=" + encode(info.collectionId()));
        return parseTrackLookup(body, info);
    }

    private Optional<Result> songSearch(String title, String artist) {
        String key = "song|" + normalize(title) + "|" + normalize(artist) + "|" + country;
        CachedTracks cached = trackCache.get(key);
        if (cached != null) {
            if (cached.expiresAt() > System.currentTimeMillis()) {
                return cached.result();
            }
            trackCache.remove(key);
        }
        try {
            String term = artist == null || artist.isBlank() ? title : title + " " + artist;
            String body = get("https://itunes.apple.com/search?media=music&entity=song&limit=10"
                    + "&country=" + encode(country) + "&term=" + encode(term));
            Optional<Result> result = parseSongSearch(body, title, artist);
            trackCache.put(key, new CachedTracks(result, expiresAt(result.isPresent())));
            return result;
        } catch (Exception e) {
            Log.warn("lookup", "song lookup failed for '" + title + "': " + e.getMessage());
            trackCache.put(key, new CachedTracks(Optional.empty(),
                    System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()));
            return Optional.empty();
        }
    }

    private static long expiresAt(boolean positive) {
        return System.currentTimeMillis() + (positive ? POSITIVE_TTL : NEGATIVE_TTL).toMillis();
    }

    private String get(String url) throws Exception {
        long wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(7))
                .header("User-Agent", "AirPlayCapture/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Downloads cover art if the sender did not provide any. Returns false on any problem. */
    public boolean downloadArtwork(String url, Path target) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String large = url.replace("/100x100bb.", "/600x600bb.")
                    .replace("/100x100.", "/600x600.");
            HttpRequest request = HttpRequest.newBuilder(URI.create(large))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "AirPlayCapture/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 5 * 1024 * 1024) {
                return false;
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!type.startsWith("image/")) {
                return false;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, response.body());
            return true;
        } catch (Exception e) {
            Log.warn("lookup", "cannot download artwork: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- parsing

    private static JsonNode safeRead(String json) {
        return JSON.readTree(json.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " "));
    }

    static Optional<AlbumInfo> parseDeezerAlbumSearch(String json, String album, String artist) {
        JsonNode data = safeRead(json).path("data");
        if (!data.isArray()) {
            return Optional.empty();
        }
        JsonNode best = null;
        double bestScore = 0;
        for (JsonNode item : data) {
            String title = item.path("title").asString("");
            String candidateArtist = item.path("artist").path("name").asString("");
            if (!matches(album, title) || !matchesAuthor(artist, candidateArtist)) {
                continue;
            }
            double score = similarity(normalize(album), normalize(title));
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String id = id(best);
        if (id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AlbumInfo(id, best.path("title").asString(""), "",
                null, best.path("cover_xl").asString(""), new ArrayList<>()));
    }

    static Optional<AlbumInfo> parseDeezerTrackSearch(String json, String title, String artist) {
        JsonNode data = safeRead(json).path("data");
        if (!data.isArray()) {
            return Optional.empty();
        }
        for (JsonNode item : data) {
            String track = item.path("title").asString("");
            String candidateArtist = item.path("artist").path("name").asString("");
            if (!matches(title, track) || !matchesAuthor(artist, candidateArtist)) {
                continue;
            }
            JsonNode album = item.path("album");
            String id = id(album);
            if (id.isBlank()) {
                continue;
            }
            return Optional.of(new AlbumInfo(id, album.path("title").asString(""), "",
                    null, album.path("cover_xl").asString(""), new ArrayList<>()));
        }
        return Optional.empty();
    }

    static AlbumInfo parseDeezerAlbum(String json, String genre, AlbumInfo base) {
        JsonNode album = safeRead(json);
        String title = album.path("title").asString("");
        if (title.isBlank()) {
            title = base.title();
        }
        String artwork = album.path("cover_xl").asString("");
        if (artwork.isBlank()) {
            artwork = base.artworkUrl();
        }
        return new AlbumInfo(base.collectionId(), title, genre,
                year(album.path("release_date").asString("")), artwork, base.tracks());
    }

    static List<TrackInfo> parseDeezerTracks(String json) {
        JsonNode data = safeRead(json).path("data");
        List<TrackInfo> tracks = new ArrayList<>();
        if (!data.isArray()) {
            return tracks;
        }
        for (JsonNode item : data) {
            tracks.add(new TrackInfo(item.path("title").asString(""),
                    item.path("track_position").isNumber() ? item.path("track_position").asInt() : null,
                    ""));
        }
        return tracks;
    }

    private static String id(JsonNode node) {
        JsonNode id = node.path("id");
        if (id.isNumber()) {
            return String.valueOf(id.asLong());
        }
        return id.asString("");
    }

    static Optional<AlbumInfo> parseAlbumSearch(String json, String album, String artist) {
        JsonNode results = JSON.readTree(json).path("results");
        if (!results.isArray()) {
            return Optional.empty();
        }
        JsonNode best = null;
        double bestScore = 0;
        for (JsonNode item : results) {
            String collection = item.path("collectionName").asString("");
            String candidateArtist = item.path("artistName").asString("");
            if (!matches(album, collection) || !matchesAuthor(artist, candidateArtist)) {
                continue;
            }
            double score = similarity(normalize(album), normalize(collection));
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String collectionId = best.path("collectionId").asString("");
        if (collectionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AlbumInfo(collectionId,
                best.path("collectionName").asString(""),
                best.path("primaryGenreName").asString(""),
                year(best.path("releaseDate").asString("")),
                artwork(best), new ArrayList<>()));
    }

    static AlbumInfo parseTrackLookup(String json, AlbumInfo info) {
        JsonNode results = JSON.readTree(json).path("results");
        List<TrackInfo> tracks = new ArrayList<>();
        String genre = info.genre();
        Integer year = info.year();
        String artwork = info.artworkUrl();
        if (results.isArray()) {
            for (JsonNode item : results) {
                if (!"track".equals(item.path("wrapperType").asString(""))) {
                    continue;
                }
                tracks.add(new TrackInfo(item.path("trackName").asString(""),
                        item.path("trackNumber").isNumber() ? item.path("trackNumber").asInt() : null,
                        artwork(item)));
                if (genre == null || genre.isBlank()) {
                    genre = item.path("primaryGenreName").asString("");
                }
                if (year == null) {
                    year = year(item.path("releaseDate").asString(""));
                }
                if (artwork == null) {
                    artwork = artwork(item);
                }
            }
        }
        return new AlbumInfo(info.collectionId(), info.title(), genre, year, artwork, tracks);
    }

    static Optional<Result> parseSongSearch(String json, String title, String artist) {
        JsonNode results = JSON.readTree(json).path("results");
        if (!results.isArray()) {
            return Optional.empty();
        }
        for (JsonNode item : results) {
            if (!"track".equals(item.path("wrapperType").asString(""))) {
                continue;
            }
            String track = item.path("trackName").asString("");
            String candidateArtist = item.path("artistName").asString("");
            if (!matches(title, track) || !matchesAuthor(artist, candidateArtist)) {
                continue;
            }
            return Optional.of(new Result(item.path("collectionName").asString(""),
                    item.path("primaryGenreName").asString(""),
                    year(item.path("releaseDate").asString("")),
                    item.path("trackNumber").isNumber() ? item.path("trackNumber").asInt() : null,
                    artwork(item)));
        }
        return Optional.empty();
    }

    static Optional<TrackInfo> matchTrack(List<TrackInfo> tracks, String title, Integer trackNumber) {
        if (tracks == null || tracks.isEmpty()) {
            return Optional.empty();
        }
        if (trackNumber != null) {
            for (TrackInfo track : tracks) {
                if (trackNumber.equals(track.trackNumber()) && matches(title, track.title())) {
                    return Optional.of(track);
                }
            }
            for (TrackInfo track : tracks) {
                if (trackNumber.equals(track.trackNumber())) {
                    return Optional.of(track);
                }
            }
        }
        for (TrackInfo track : tracks) {
            if (matches(title, track.title())) {
                return Optional.of(track);
            }
        }
        return Optional.empty();
    }

    private static String artwork(JsonNode item) {
        String url = item.path("artworkUrl100").asString("");
        return url;
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

    static boolean matches(String query, String candidate) {
        String a = normalize(query);
        String b = normalize(candidate);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        return similarity(a, b) >= 0.85;
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
        return similarity(a, b) >= 0.8;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return decomposed.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static double similarity(String a, String b) {
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
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
