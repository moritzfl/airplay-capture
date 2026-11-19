package de.moritzf.airplaycapture.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    private static final String RELEASE = """
    {"tag_name":"1.2.0","name":"AirPlay Capture 1.2.0","draft":false,"prerelease":false,
     "html_url":"https://github.com/moritzfl/airplay-capture/releases/tag/1.2.0",
     "published_at":"2026-11-20T10:00:00Z","assets":[]}
    """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>(RELEASE);
    private final AtomicReference<String> userAgent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", exchange -> {
            requests.incrementAndGet();
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private UpdateChecker checker(String version, Settings settings, UiState ui) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        return new UpdateChecker(version, settings, ui, endpoint);
    }

    @Test
    void comparesVersions() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.0"));
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.3"));
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"));
        assertTrue(UpdateChecker.isNewer("v1.1.1", "1.1.0"));
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"));
        assertFalse(UpdateChecker.isNewer("1.0.3", "1.1.0"));
    }

    @Test
    void aSnapshotIsOlderThanItsRelease() {
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1.1-SNAPSHOT"));
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.1-SNAPSHOT"));
    }

    @Test
    void unknownVersionsNeverReportAnUpdate() {
        assertFalse(UpdateChecker.isNewer("1.2.0", ""));
        assertFalse(UpdateChecker.isNewer("1.2.0", null));
        assertFalse(UpdateChecker.isNewer("nightly", "1.1.0"));
    }

    @Test
    void parsesTheReleaseGitHubReturns() {
        UpdateChecker.Release release = UpdateChecker.parseRelease(RELEASE).orElseThrow();
        assertEquals("1.2.0", release.version());
        assertEquals("https://github.com/moritzfl/airplay-capture/releases", release.url());
        assertEquals("2026-11-20T10:00:00Z", release.publishedAt());
        assertTrue(UpdateChecker.parseRelease("{\"message\":\"Not Found\"}").isEmpty());
        assertTrue(UpdateChecker.parseRelease("not json").isEmpty());
    }

    @Test
    void reportsANewerReleaseAndNotifiesTheUi() {
        UiState ui = new UiState();
        AtomicInteger changes = new AtomicInteger();
        ui.addListener(changes::incrementAndGet);
        try (UpdateChecker checker = checker("1.1.0", new Settings(), ui)) {
            UpdateChecker.Status status = checker.checkNow();
            assertTrue(status.updateAvailable());
            assertEquals("1.2.0", status.latest().version());
            assertEquals("", status.error());
            assertTrue(status.checkedAtEpochMs() > 0);
            assertEquals(1, changes.get());
            assertEquals("AirPlayCapture/1.1.0", userAgent.get());
        }
    }

    @Test
    void theInstalledReleaseIsUpToDate() {
        try (UpdateChecker checker = checker("1.2.0", new Settings(), new UiState())) {
            UpdateChecker.Status status = checker.checkNow();
            assertFalse(status.updateAvailable());
            assertEquals("1.2.0", status.latest().version());
        }
    }

    @Test
    void aFailedCheckKeepsTheEarlierResult() {
        try (UpdateChecker checker = checker("1.1.0", new Settings(), new UiState())) {
            checker.checkNow();
            responseCode.set(403);
            body.set("{\"message\":\"API rate limit exceeded\"}");
            UpdateChecker.Status status = checker.checkNow();
            assertTrue(status.updateAvailable(), "the release found earlier is still newer");
            assertEquals("GitHub answered with HTTP 403", status.error());
        }
    }

    @Test
    void turningTheCheckOffForgetsTheResultAndAsksNoMore() throws Exception {
        Settings settings = new Settings();
        try (UpdateChecker checker = checker("1.1.0", settings, new UiState())) {
            checker.checkNow();
            settings.updateCheckEnabled = false;
            checker.settingsChanged();
            assertNull(checker.status().latest());
            assertFalse(checker.status().updateAvailable());
            int before = requests.get();
            Thread.sleep(200);
            assertEquals(before, requests.get());
        }
    }

    @Test
    void turningTheCheckOnLooksRightAway() throws Exception {
        Settings settings = new Settings();
        settings.updateCheckEnabled = false;
        try (UpdateChecker checker = checker("1.1.0", settings, new UiState())) {
            settings.updateCheckEnabled = true;
            checker.settingsChanged();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!checker.status().updateAvailable() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(checker.status().updateAvailable());
        }
    }
}
