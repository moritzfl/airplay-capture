package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.Registration;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import de.moritzf.airplaycapture.util.UpdateChecker;
import org.springframework.beans.factory.annotation.Value;

/** Application shell with brand, live status and navigation. */
public class MainLayout extends VerticalLayout implements RouterLayout, AfterNavigationObserver {

    private final UiState state;
    private final UiBroadcaster broadcaster;
    private final Div content = new Div();
    private final Span deviceName = new Span();
    private final Span statusText = new Span();
    private final Span statusDetail = new Span();
    private final Span statusPill = new Span();
    private final Span queuePill = new Span();
    private final Span updatePill = new Span();
    private final RouterLink settingsLink;
    private final UpdateChecker updateChecker;

    private static final String APP_NAME = "AirPlay Capture";

    private final AudiobookJobs audiobookJobs;
    private final Anchor logout;
    private Registration registration;

    public MainLayout(
        UiState state,
        UiBroadcaster broadcaster,
        AudiobookJobs audiobookJobs,
        UpdateChecker updateChecker,
        @Value("${airplay-capture.web.password:}") String password
    ) {
        this.state = state;
        this.broadcaster = broadcaster;
        this.audiobookJobs = audiobookJobs;
        this.updateChecker = updateChecker;
        boolean authEnabled = password != null && !password.isBlank();

        addClassName("app-shell");
        setSizeFull();
        setAlignItems(Alignment.STRETCH);
        setPadding(false);
        setSpacing(false);

        Div header = new Div();
        header.addClassName("app-header");
        header.setWidthFull();

        Div brand = new Div();
        brand.addClassName("brand");
        Image logo = new Image(AppUrls.relative("", "icons/icon-64.png"), "");
        logo.addClassName("logo");
        Div titles = new Div();
        titles.addClassName("brand-titles");
        H1 title = new H1("AirPlay Capture");
        title.addClassName("brand-title");
        deviceName.addClassName("brand-device");
        titles.add(title, deviceName);
        brand.add(logo, titles);

        Span dot = new Span();
        dot.addClassName("status-dot");
        statusText.addClassName("status-text");
        statusDetail.addClassName("status-detail");
        statusPill.addClassName("status-pill");
        statusPill.getElement().setAttribute("role", "status");
        statusPill.add(dot, statusText, statusDetail);

        Div nav = new Div();
        nav.addClassName("app-nav");
        settingsLink = navLink("Settings", SettingsView.class);
        nav.add(
            navLink("Library", LibraryView.class),
            settingsLink,
            navLink("Logs", LogsView.class)
        );
        logout = authEnabled ? anchorLink("Log out", AppUrls.relative("logout")) : null;
        if (logout != null) {
            nav.add(logout);
        }

        queuePill.addClassName("queue-pill");
        queuePill.setVisible(false);
        queuePill.getElement().setAttribute("tabindex", "0");
        queuePill.getElement().setAttribute("aria-label", "Audiobook conversions");
        queuePill
            .getElement()
            .addEventListener("keydown", event -> openLibraryQueue())
            .setFilter("event.key === 'Enter' || event.key === ' '");
        queuePill.getElement().setAttribute("role", "status");
        queuePill.addClickListener(event -> openLibraryQueue());

        updatePill.addClassName("update-pill");
        updatePill.setVisible(false);
        updatePill.getElement().setAttribute("role", "link");
        updatePill.getElement().setAttribute("tabindex", "0");
        updatePill
            .getElement()
            .addEventListener("keydown", event -> openUpdates())
            .setFilter("event.key === 'Enter' || event.key === ' '");
        updatePill.addClickListener(event -> openUpdates());

        Div right = new Div();
        right.addClassName("app-header-right");
        right.add(updatePill, queuePill, statusPill, nav);

        header.add(brand, right);

        content.addClassName("app-content");
        content.setWidthFull();
        add(header, content, new AudioPlayer());
        setFlexGrow(1, content);

        refreshStatus();
    }

    private RouterLink navLink(
        String label,
        Class<? extends com.vaadin.flow.component.Component> view
    ) {
        RouterLink link = new RouterLink(label, view);
        link.addClassName("nav-link");
        link.setHighlightCondition((target, event) ->
            event
                .getActiveChain()
                .stream()
                .anyMatch(component -> component.getClass().equals(view))
        );
        link.setHighlightAction((target, highlight) -> {
            if (highlight) {
                target.addClassName("active");
            } else {
                target.removeClassName("active");
            }
        });
        return link;
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        if (logout != null) {
            logout.setHref(AppUrls.relative("logout"));
        }
    }

    private Anchor anchorLink(String label, String href) {
        Anchor anchor = new Anchor(href, label);
        anchor.addClassName("nav-link");
        return anchor;
    }

    private void onStateChanged() {
        UI ui = getUI().orElse(null);
        if (ui == null) {
            return;
        }
        ui.access(this::refreshStatus);
    }

    /** Header badge that keeps conversions visible from every page. */
    private void refreshQueue() {
        var active = audiobookJobs.active();
        queuePill.setVisible(!active.isEmpty());
        if (active.isEmpty()) {
            return;
        }
        queuePill.removeClassNames("running", "waiting");
        long running = active
            .stream()
            .filter(job -> job.status() == AudiobookJobs.State.RUNNING)
            .count();
        boolean isRunning = running > 0;
        queuePill.addClassName(isRunning ? "running" : "waiting");
        AudiobookJobs.Job first = active.get(0);
        // Three parts, so a phone can show just the number that changes.
        Span label = new Span(isRunning ? "Converting · " : "");
        label.addClassName("queue-pill-label");
        Span value = new Span(
            isRunning ? Math.round(first.progress * 100) + "%" : String.valueOf(active.size())
        );
        Span more = new Span(
            !isRunning ? " waiting" : active.size() > 1 ? " · " + active.size() + " in queue" : ""
        );
        more.addClassName("queue-pill-label");
        queuePill.removeAll();
        queuePill.add(label, value, more);
        queuePill
            .getElement()
            .setAttribute(
                "title",
                isRunning
                    ? "Converting \u201c" +
                          first.title +
                          "\u201d \u2014 open the library to see the queue"
                    : "Audiobook conversions \u2014 open the library to see the queue"
            );
    }

    /** Opens the library, where the conversion queue sits above the recordings. */
    private void openLibraryQueue() {
        getUI().ifPresent(ui -> {
            ui.navigate(LibraryView.class);
            ui.getPage().executeJs(
                "requestAnimationFrame(() => document.querySelector('.queue-card')" +
                    "?.scrollIntoView({block: 'nearest', behavior: 'smooth'}))"
            );
        });
    }

    /**
     * A newer release is announced where it cannot be missed but does not interrupt: a pill in
     * the header and a dot on the Settings link, both leading to the details in the settings.
     */
    private void refreshUpdate() {
        UpdateChecker.Status status = updateChecker.status();
        boolean available = status.updateAvailable() && status.latest() != null;
        updatePill.setVisible(available);
        settingsLink.getElement().getClassList().set("has-update", available);
        if (available) {
            String version = status.latest().version();
            updatePill.setText("Update " + version);
            String hint = "Version " + version + " is available. Open Settings for details.";
            updatePill.getElement().setAttribute("title", hint);
            updatePill.getElement().setAttribute("aria-label", hint);
            settingsLink.getElement().setAttribute("title", "Version " + version + " is available");
        } else {
            settingsLink.getElement().removeAttribute("title");
        }
    }

    /** Opens the settings at the Updates card. */
    private void openUpdates() {
        getUI().ifPresent(ui -> {
            ui.navigate(SettingsView.class);
            ui.getPage().executeJs(
                "requestAnimationFrame(() => document.getElementById('" +
                    SettingsView.UPDATES_ID +
                    "')?.scrollIntoView({block: 'start', behavior: 'smooth'}))"
            );
        });
    }

    private void refreshStatus() {
        refreshQueue();
        refreshUpdate();
        // The receiver name is only worth a second line when it differs from the product name.
        String name = state.deviceName == null ? "" : state.deviceName.trim();
        deviceName.setText(name);
        deviceName.setVisible(!name.isBlank() && !name.equalsIgnoreCase(APP_NAME));
        statusPill.removeClassNames("live", "ok", "offline");
        String detail;
        if (state.receiving) {
            statusPill.addClassName("live");
            statusText.setText("Recording");
            String source = isBlank(state.clientName) ? state.client : state.clientName;
            detail = isBlank(source)
                ? ""
                : "from " + source + (isBlank(state.senderApp) ? "" : " · " + state.senderApp);
        } else if (state.advertising) {
            statusPill.addClassName("ok");
            statusText.setText("Ready");
            detail =
                (isBlank(state.interfaceAddress) ? "" : state.interfaceAddress + " · ") +
                "port " +
                state.port;
        } else {
            statusPill.addClassName("offline");
            statusText.setText("Receiver offline");
            detail = state.lastError == null ? "" : state.lastError;
        }
        statusDetail.setText(detail);
        statusDetail.setVisible(!detail.isBlank());
        String hint = state.receiving
            ? isBlank(state.client)
                ? ""
                : "Sender address " + state.client
            : state.advertising
              ? "Visible in the AirPlay menu as \u201c" + name + "\u201d"
              : "The receiver is not visible in the AirPlay menu. See Logs for details.";
        statusPill.getElement().setAttribute("title", hint);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void showRouterLayoutContent(HasElement content) {
        this.content.getElement().removeAllChildren();
        this.content.getElement().appendChild(content.getElement());
    }

    @Override
    public void removeRouterLayoutContent(HasElement content) {
        this.content.getElement().removeChild(content.getElement());
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (registration == null) {
            registration = broadcaster.register(this::onStateChanged);
            getUI().ifPresent(ui -> {
                ui.getElement().getThemeList().add("dark");
                // The interface is English whatever the browser prefers; the page says so, so
                // screen readers and hyphenation do not treat it as the browser's language.
                ui.getPage().executeJs("document.documentElement.lang = 'en'");
            });
            refreshStatus();
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        super.onDetach(detachEvent);
    }
}
