package de.moritzf.airplaycapture.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppUrlsTest {

    @Test
    void libraryRouteStaysInsideTheProxyPrefix() {
        assertEquals("../api/tracks/1/file/m4a?download=1",
                AppUrls.relative("", "api/tracks/1/file/m4a?download=1"));
    }

    @Test
    void deeperRoutesClimbBackToTheContextRoot() {
        assertEquals("../../logout", AppUrls.relative("settings", "/logout"));
        assertEquals("../../../api/x", AppUrls.relative("a/b", "api/x"));
    }
}
