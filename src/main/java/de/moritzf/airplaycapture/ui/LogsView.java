package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import de.moritzf.airplaycapture.Log;

import java.util.List;

/** Live log of the receiver and export pipeline. */
@Route(value = "logs", layout = MainLayout.class)
public class LogsView extends VerticalLayout {

    private final UiBroadcaster broadcaster;
    private final Div lines = new Div();
    private Registration registration;
    private int renderedLines;
    private String renderedLast = "";

    public LogsView(UiBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
        addClassName("logs-view");
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        setAlignItems(Alignment.STRETCH);

        H2 heading = new H2("Logs");
        heading.addClassName("view-heading");
        lines.addClassName("log-lines");

        add(heading, lines);
        render();
    }

    private void render() {
        List<Log.Line> snapshot = Log.lines();
        String last = snapshot.isEmpty() ? "" : snapshot.get(snapshot.size() - 1).time()
                + "|" + snapshot.get(snapshot.size() - 1).message();
        if (snapshot.size() == renderedLines && last.equals(renderedLast)) {
            return;
        }
        renderedLines = snapshot.size();
        renderedLast = last;
        lines.removeAll();
        for (Log.Line line : snapshot) {
            Div row = new Div();
            row.addClassName("log-line");
            Span time = new Span(line.time());
            time.addClassName("log-time");
            Span level = new Span(line.level());
            level.addClassName("log-level");
            level.addClassName(line.level());
            Span scope = new Span(line.scope());
            scope.addClassName("log-scope");
            Span message = new Span(line.message());
            message.addClassName("log-message");
            row.add(time, level, scope, message);
            lines.add(row);
        }
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
