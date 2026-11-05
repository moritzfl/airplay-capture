package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
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
import org.springframework.beans.factory.annotation.Value;

/** Application shell with brand, live status and navigation. */
public class MainLayout extends VerticalLayout implements RouterLayout, AfterNavigationObserver {

    private final UiState state;
    private final UiBroadcaster broadcaster;
    private final Div content = new Div();
    private final Span deviceName = new Span();
    private final Span statusText = new Span();
    private final Span statusPill = new Span();
    private final Span queuePill = new Span();

    private final AudiobookJobs audiobookJobs;
    private final Anchor logout;
    private Registration registration;

    public MainLayout(UiState state, UiBroadcaster broadcaster, AudiobookJobs audiobookJobs,
                      @Value("${airplay-capture.web.password:}") String password) {
        this.state = state;
        this.broadcaster = broadcaster;
        this.audiobookJobs = audiobookJobs;
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
        Span logo = new Span("◉");
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
        statusPill.addClassName("status-pill");
        statusPill.add(dot, statusText);

        Div nav = new Div();
        nav.addClassName("app-nav");
        nav.add(navLink("Library", LibraryView.class), navLink("Settings", SettingsView.class),
                navLink("Logs", LogsView.class));
        logout = authEnabled ? anchorLink("Log out", AppUrls.relative("logout")) : null;
        if (logout != null) {
            nav.add(logout);
        }

        queuePill.addClassName("queue-pill");
        queuePill.setVisible(false);
        queuePill.getElement().setAttribute("tabindex", "0");
        queuePill.getElement().setAttribute("aria-label", "Audiobook conversions");
        queuePill.getElement().addEventListener("keydown",
                event -> openLibraryQueue()).setFilter("event.key === 'Enter' || event.key === ' '");
        queuePill.getElement().setAttribute("role", "status");
        queuePill.addClickListener(event -> openLibraryQueue());

        Div right = new Div();
        right.addClassName("app-header-right");
        right.add(queuePill, statusPill, nav);

        header.add(brand, right);

        content.addClassName("app-content");
        content.setWidthFull();
        add(header, content);
        setFlexGrow(1, content);

        refreshStatus();
    }

    private RouterLink navLink(String label, Class<? extends com.vaadin.flow.component.Component> view) {
        RouterLink link = new RouterLink(label, view);
        link.addClassName("nav-link");
        link.setHighlightCondition((target, event) -> event.getActiveChain().stream()
                .anyMatch(component -> component.getClass().equals(view)));
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
        long running = active.stream()
                .filter(job -> job.status() == AudiobookJobs.State.RUNNING).count();
        boolean isRunning = running > 0;
        queuePill.addClassName(isRunning ? "running" : "waiting");
        AudiobookJobs.Job first = active.get(0);
        String label = isRunning
                ? "Converting · " + Math.round(first.progress * 100) + "%"
                : active.size() + " waiting";
        if (active.size() > 1) {
            label += " · " + active.size() + " in queue";
        }
        queuePill.setText(label);
        queuePill.getElement().setAttribute("title", isRunning
                ? "Converting \u201c" + first.title + "\u201d \u2014 open the library to see the queue"
                : "Audiobook conversions \u2014 open the library to see the queue");
    }

    /** Opens the library, where the conversion queue sits above the recordings. */
    private void openLibraryQueue() {
        getUI().ifPresent(ui -> {
            ui.navigate(LibraryView.class);
            ui.getPage().executeJs("requestAnimationFrame(() => document.querySelector('.queue-card')"
                    + "?.scrollIntoView({block: 'nearest', behavior: 'smooth'}))");
        });
    }

    private void refreshStatus() {
        refreshQueue();
        deviceName.setText(state.deviceName == null ? "" : state.deviceName);
        statusPill.removeClassNames("live", "ok", "offline");
        if (state.receiving) {
            statusPill.addClassName("live");
            String source = state.clientName == null || state.clientName.isBlank()
                    ? state.client : state.clientName;
            statusText.setText(source == null || source.isBlank()
                    ? "Recording"
                    : "Recording from " + source
                    + (state.senderApp == null || state.senderApp.isBlank()
                            ? "" : " · " + state.senderApp));
            statusPill.getElement().setAttribute("title",
                    state.client == null || state.client.isBlank() ? "" : "IP " + state.client);
        } else if (state.advertising) {
            statusPill.addClassName("ok");
            statusText.setText("Ready on port " + state.port
                    + (state.interfaceAddress.isBlank() ? "" : " via " + state.interfaceAddress));
        } else {
            statusPill.addClassName("offline");
            String error = state.lastError == null ? "" : state.lastError;
            statusText.setText(error.isBlank() ? "Receiver offline" : "Receiver offline: " + error);
        }
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
            getUI().ifPresent(ui -> ui.getElement().getThemeList().add("dark"));
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
