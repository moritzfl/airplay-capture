package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.UI;

/**
 * URLs that stay valid when the UI is served under a reverse-proxy prefix.
 * A leading {@code /} would escape the prefix; {@code context://} is rejected
 * by Vaadin on {@code Anchor} hrefs.
 */
public final class AppUrls {

    private AppUrls() {
    }

    /** Path from the servlet context root, for example {@code api/tracks/1/cover}. */
    static String relative(String pathFromContextRoot) {
        String location = "";
        UI ui = UI.getCurrent();
        if (ui != null) {
            location = ui.getActiveViewLocation().getPath();
        }
        return relative(location, pathFromContextRoot);
    }

    public static String relative(String locationPath, String pathFromContextRoot) {
        int depth = locationPath == null || locationPath.isBlank()
                ? 0 : locationPath.split("/").length;
        String path = pathFromContextRoot.startsWith("/")
                ? pathFromContextRoot.substring(1) : pathFromContextRoot;
        // The UI lives under /app/, so leave that segment plus any route segments.
        return "../".repeat(depth + 1) + path;
    }
}
