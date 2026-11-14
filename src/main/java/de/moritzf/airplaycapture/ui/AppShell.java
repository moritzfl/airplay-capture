package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.lumo.Lumo;

/** Vaadin application shell: server push, dark color scheme, Lumo and the custom stylesheet. */
@Push
@ColorScheme(ColorScheme.Value.DARK)
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("context://app.css")
public class AppShell implements AppShellConfigurator {

    @Override
    public void configurePage(AppShellSettings settings) {
        // Relative to the document, so the icons also resolve behind a reverse-proxy prefix.
        settings.addFavIcon("icon", "../icons/icon-64.png", "64x64");
        settings.addFavIcon("icon", "../icons/icon-192.png", "192x192");
        settings.addFavIcon("apple-touch-icon", "../icons/icon-192.png", "192x192");
    }
}
