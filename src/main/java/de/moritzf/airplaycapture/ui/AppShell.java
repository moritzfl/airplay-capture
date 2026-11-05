package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;

/** Vaadin application shell: server push, dark color scheme and the custom stylesheet. */
@Push
@ColorScheme(ColorScheme.Value.DARK)
@StyleSheet("context://app.css")
@Theme(themeClass = Lumo.class)
public class AppShell implements AppShellConfigurator {
}
