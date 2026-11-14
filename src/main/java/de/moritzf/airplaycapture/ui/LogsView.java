package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import de.moritzf.airplaycapture.Log;
import java.util.List;
import java.util.stream.Collectors;

/** Live log of the receiver and export pipeline, newest line last. */
@Route(value = "logs", layout = MainLayout.class)
public class LogsView extends VerticalLayout {

    /**
     * Copies the text and reports back whether it worked. The Clipboard API needs a secure
     * context, which a plain-HTTP address on the LAN is not, so there is a fallback.
     */
    private static final String COPY_JS = """
    const text = $0;
    const legacy = () => {
      const area = document.createElement('textarea');
      area.value = text;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      let done = false;
      try { done = document.execCommand('copy'); } catch (e) { done = false; }
      area.remove();
      return done;
    };
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(() => true, legacy);
    }
    return legacy();
    """;

    /** Keeps the newest line in view, unless the reader scrolled up to look at something. */
    private static final String FOLLOW_JS = """
    const box = this;
    if (box.dataset.follow !== 'false') {
      box.scrollTop = box.scrollHeight;
    }
    if (!box.dataset.ready) {
      box.dataset.ready = 'true';
      box.addEventListener('scroll', () => {
        box.dataset.follow = String(box.scrollHeight - box.scrollTop - box.clientHeight < 40);
      });
    }
    """;

    private final UiBroadcaster broadcaster;
    private final Div lines = new Div();
    private final Span count = new Span();
    private final Checkbox problemsOnly = new Checkbox("Warnings and errors only");
    private final Button copy = new Button("Copy", VaadinIcon.COPY_O.create());
    private Registration registration;
    private String rendered = "";

    public LogsView(UiBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
        addClassName("logs-view");
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        setAlignItems(Alignment.STRETCH);

        H2 heading = new H2("Logs");
        heading.addClassName("view-heading");
        count.addClassName("count");

        problemsOnly.addValueChangeListener(event -> render());
        copy.addThemeVariants(ButtonVariant.LUMO_SMALL);
        copy.getElement().setAttribute("title", "Copy the lines shown, for a bug report");
        copy.addClickListener(event -> copyLines());

        Div title = new Div(heading, count);
        title.addClassName("lib-heading");
        Div tools = new Div(problemsOnly, copy);
        tools.addClassName("log-tools");
        Div toolbar = new Div(title, tools);
        toolbar.addClassName("lib-toolbar");

        lines.addClassName("log-lines");
        lines.getElement().setAttribute("role", "log");
        lines.getElement().setAttribute("aria-label", "Application log");
        lines.getElement().setAttribute("tabindex", "0");

        add(toolbar, lines);
        render();
    }

    private List<Log.Line> visibleLines() {
        List<Log.Line> all = Log.lines();
        if (!problemsOnly.getValue()) {
            return all;
        }
        return all
            .stream()
            .filter(line -> "WARN".equals(line.level()) || "ERROR".equals(line.level()))
            .toList();
    }

    private void render() {
        List<Log.Line> snapshot = visibleLines();
        String signature =
            problemsOnly.getValue() +
            "|" +
            snapshot.size() +
            "|" +
            (snapshot.isEmpty()
                ? ""
                : snapshot.get(snapshot.size() - 1).time() +
                  snapshot.get(snapshot.size() - 1).message());
        if (signature.equals(rendered)) {
            return;
        }
        rendered = signature;
        count.setText(snapshot.size() + (snapshot.size() == 1 ? " line" : " lines"));
        copy.setEnabled(!snapshot.isEmpty());
        lines.removeAll();
        if (snapshot.isEmpty()) {
            Span empty = new Span(
                problemsOnly.getValue() ? "No warnings or errors." : "Nothing logged yet."
            );
            empty.addClassName("log-empty");
            lines.add(empty);
            return;
        }
        for (Log.Line line : snapshot) {
            Div row = new Div();
            row.addClassName("log-line");
            row.addClassName(line.level());
            Span time = new Span(line.time());
            time.addClassName("log-time");
            Span level = new Span(line.level());
            level.addClassName("log-level");
            Span scope = new Span(line.scope());
            scope.addClassName("log-scope");
            Span message = new Span(line.message());
            message.addClassName("log-message");
            row.add(time, level, scope, message);
            lines.add(row);
        }
        lines.getElement().executeJs(FOLLOW_JS);
    }

    private void copyLines() {
        List<Log.Line> snapshot = visibleLines();
        String text = snapshot
            .stream()
            .map(
                line -> line.time() + " " + line.level() + " " + line.scope() + " " + line.message()
            )
            .collect(Collectors.joining("\n"));
        copy.getElement()
            .executeJs(COPY_JS, text)
            .then(Boolean.class, done -> {
                copy.setText(Boolean.TRUE.equals(done) ? "Copied" : "Copy failed");
                copy.getElement().executeJs(
                    "setTimeout(() => $0.$server.resetCopy(), 2000)",
                    getElement()
                );
            });
    }

    @com.vaadin.flow.component.ClientCallable
    private void resetCopy() {
        copy.setText("Copy");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        registration = broadcaster.register(() -> {
            UI ui = getUI().orElse(null);
            if (ui != null) {
                ui.access(this::render);
            }
        });
        render();
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
