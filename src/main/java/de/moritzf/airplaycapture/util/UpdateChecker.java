package de.moritzf.airplaycapture.util;

import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks GitHub once a day whether a newer release exists, so the web UI can say so. Only the
 * public "latest release" of the project is read; nothing about this installation is sent
 * besides the request itself. The check can be turned off in the settings.
 */
public final class UpdateChecker implements AutoCloseable {

    public static final URI LATEST_RELEASE = URI.create(
        "https://api.github.com/repos/moritzfl/airplay-capture/releases/latest"
    );

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final long FIRST_CHECK_SECONDS = 20;
    private static final long INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(24);

    /** A published release: its version, its page and when it was published (ISO-8601). */
    public record Release(String version, String url, String publishedAt) {}

    /**
     * What is known right now. {@code latest} is the newest release seen, {@code error} the reason
     * the last check failed (empty when it worked), {@code checkedAtEpochMs} 0 before any check.
     */
    public record Status(
        String currentVersion,
        Release latest,
        boolean updateAvailable,
        long checkedAtEpochMs,
        String error
    ) {}

    private final String currentVersion;
    private final Settings settings;
    private final UiState ui;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
            Thread thread = new Thread(runnable, "update-check");
            thread.setDaemon(true);
            return thread;
        }
    );

    private volatile Status status;

    /**
     * @param currentVersion version of the running build; blank for a development run, which
     *                       never reports an update
     */
    public UpdateChecker(String currentVersion, Settings settings, UiState ui) {
        this(currentVersion, settings, ui, LATEST_RELEASE);
    }

    UpdateChecker(String currentVersion, Settings settings, UiState ui, URI endpoint) {
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.settings = settings;
        this.ui = ui;
        this.endpoint = endpoint;
        this.status = new Status(this.currentVersion, null, false, 0, "");
    }

    /** Starts the daily check; the first one runs shortly after startup. */
    public void start() {
        scheduler.scheduleWithFixedDelay(
            this::scheduledCheck,
            FIRST_CHECK_SECONDS,
            INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    public Status status() {
        return status;
    }

    public boolean enabled() {
        return settings.updateCheckEnabled;
    }

    /** Called after the settings were saved: forget the result when off, look right away when on. */
    public void settingsChanged() {
        if (!settings.updateCheckEnabled) {
            if (status.latest() != null || !status.error().isEmpty()) {
                status = new Status(currentVersion, null, false, 0, "");
                ui.changed();
            }
        } else if (status.checkedAtEpochMs() == 0) {
            scheduler.execute(this::scheduledCheck);
        }
    }

    private void scheduledCheck() {
        if (settings.updateCheckEnabled) {
            checkNow();
        }
    }

    /** Asks GitHub now, regardless of the schedule, and returns the new status. */
    public synchronized Status checkNow() {
        Status before = status;
        Status next;
        try {
            Release latest = fetchLatest();
            next = new Status(
                currentVersion,
                latest,
                isNewer(latest.version(), currentVersion),
                System.currentTimeMillis(),
                ""
            );
            if (next.updateAvailable() && !sameRelease(before.latest(), latest)) {
                Log.info(
                    "update",
                    "version " + latest.version() + " is available: " + latest.url()
                );
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String reason =
                e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            // Keep what an earlier check found; a NAS without internet access is not an error
            // worth repeating in the log every day.
            next = new Status(
                currentVersion,
                before.latest(),
                before.updateAvailable(),
                System.currentTimeMillis(),
                reason
            );
            if (before.error().isEmpty()) {
                Log.warn("update", "cannot check for a new version: " + reason);
            }
        }
        status = next;
        ui.changed();
        return next;
    }

    private Release fetchLatest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header(
                "User-Agent",
                "AirPlayCapture/" + (currentVersion.isEmpty() ? "dev" : currentVersion)
            )
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub answered with HTTP " + response.statusCode());
        }
        return parseRelease(response.body()).orElseThrow(() ->
            new IllegalStateException("GitHub returned no release")
        );
    }

    /** Reads the fields of GitHub's release JSON that matter here. */
    static Optional<Release> parseRelease(String json) {
        JsonNode release;
        try {
            release = JSON.readTree(json);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String tag = release.path("tag_name").asString("").trim();
        if (tag.isEmpty() || parse(tag) == null) {
            return Optional.empty();
        }
        return Optional.of(
            new Release(
                tag.startsWith("v") ? tag.substring(1) : tag,
                "https://github.com/moritzfl/airplay-capture/releases",
                release.path("published_at").asString("")
            )
        );
    }

    /**
     * True when {@code candidate} is a later version than {@code current}. A {@code -SNAPSHOT}
     * or other pre-release of a version is older than that version itself; a blank or
     * unreadable version never has an update.
     */
    public static boolean isNewer(String candidate, String current) {
        Version latest = parse(candidate);
        Version running = parse(current);
        if (latest == null || running == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (latest.numbers[i] != running.numbers[i]) {
                return latest.numbers[i] > running.numbers[i];
            }
        }
        return running.preRelease && !latest.preRelease;
    }

    private record Version(int[] numbers, boolean preRelease) {}

    private static Version parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("v")) {
            value = value.substring(1);
        }
        int dash = value.indexOf('-');
        String core = dash < 0 ? value : value.substring(0, dash);
        String[] parts = core.split("\\.");
        if (core.isEmpty() || parts.length > 3) {
            return null;
        }
        int[] numbers = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new Version(numbers, dash >= 0);
    }

    private static boolean sameRelease(Release a, Release b) {
        return a != null && b != null && a.version().equals(b.version());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
