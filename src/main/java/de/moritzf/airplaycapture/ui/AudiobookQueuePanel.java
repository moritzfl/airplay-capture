package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Element;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import java.util.List;
import java.util.Locale;

/**
 * Persistent list of audiobook conversions: what is queued, what is running and how far it is,
 * and where the finished file can be downloaded. Finished entries stay until they are dismissed,
 * so the answer to "where did my audiobook go?" is always on screen.
 */
final class AudiobookQueuePanel extends Div {

    /** Called when the user asks to show a finished book in the library. */
    interface Reveal {
        void showInLibrary(AudiobookJobs.Job job);

        void changed();

        /** Opens the conversion dialog again for the recordings of a failed or cancelled job. */
        void retry(AudiobookJobs.Job job);

        /** True when a finished build was replaced by a newer build of the same book. */
        boolean replaced(AudiobookJobs.Job job);
    }

    private final AudiobookJobs jobs;
    private final Reveal reveal;
    private final Div list = new Div();
    private final Span summary = new Span();
    private final Button clear = new Button("Clear finished");
    private String signature = "";

    AudiobookQueuePanel(AudiobookJobs jobs, Reveal reveal) {
        this.jobs = jobs;
        this.reveal = reveal;

        addClassName("queue-card");
        setVisible(false);

        Div head = new Div();
        head.addClassName("queue-head");
        Span icon = new Span();
        icon.addClassName("queue-icon");
        icon.getElement().appendChild(VaadinIcon.CLOUD_DOWNLOAD.create().getElement());
        Span title = new Span("Audiobook conversions");
        title.addClassName("queue-title");
        summary.addClassName("queue-summary");
        summary.getElement().setAttribute("aria-live", "polite");
        clear.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        clear.addClassName("queue-clear");
        clear
            .getElement()
            .setAttribute(
                "title",
                "Remove finished conversions from this list. Files in the library are kept."
            );
        clear.addClickListener(event -> {
            jobs.clearFinished();
            reveal.changed();
        });
        head.add(icon, title, summary, clear);

        list.addClassName("queue-list");
        list.getElement().setAttribute("role", "list");
        add(head, list);
        refresh();
    }

    /** Rebuilds the panel only when a visible value changed. */
    void refresh() {
        List<AudiobookJobs.Job> all = jobs.jobs();
        String next = signature(all);
        setVisible(!all.isEmpty());
        if (next.equals(signature)) {
            return;
        }
        signature = next;
        summary.setText(summary(all));
        clear.setVisible(all.stream().anyMatch(job -> !job.active()));
        list.removeAll();
        long finished = all
            .stream()
            .filter(job -> !job.active())
            .count();
        int shown = 0;
        for (AudiobookJobs.Job job : all) {
            if (!job.active() && shown >= VISIBLE_FINISHED) {
                continue;
            }
            if (!job.active()) {
                shown++;
            }
            list.add(row(job));
        }
        if (finished > VISIBLE_FINISHED) {
            long hidden = finished - VISIBLE_FINISHED;
            Span more = new Span(
                hidden +
                    " older " +
                    (hidden == 1 ? "conversion" : "conversions") +
                    " hidden. Clearing this list never deletes files."
            );
            more.addClassName("queue-more");
            list.add(more);
        }
    }

    private static String signature(List<AudiobookJobs.Job> all) {
        StringBuilder builder = new StringBuilder();
        for (AudiobookJobs.Job job : all) {
            builder
                .append(job.id)
                .append('|')
                .append(job.state)
                .append('|')
                .append(job.step)
                .append('|')
                .append(String.format(Locale.ROOT, "%.3f", job.progress))
                .append('|')
                .append(job.resultTrackId)
                .append('|')
                .append(job.error)
                .append(';');
        }
        return builder.toString();
    }

    private static String summary(List<AudiobookJobs.Job> all) {
        long running = all
            .stream()
            .filter(job -> job.status() == AudiobookJobs.State.RUNNING)
            .count();
        long queued = all
            .stream()
            .filter(job -> job.status() == AudiobookJobs.State.QUEUED)
            .count();
        if (running + queued == 0) {
            return "Nothing converting";
        }
        StringBuilder text = new StringBuilder();
        if (running > 0) {
            text.append(running).append(" converting");
        }
        if (queued > 0) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(queued).append(" waiting");
        }
        return text.toString();
    }

    private Div row(AudiobookJobs.Job job) {
        Div row = new Div();
        row.setId(jobAnchor(job.id));
        row.addClassName("queue-row");
        if (System.currentTimeMillis() - job.createdAtEpochMs < FRESH_MS) {
            // The row is rebuilt on every progress tick, so the highlight is derived from the
            // job age instead of a one-off animation that a re-render would drop.
            row.addClassName("queue-fresh");
        }
        row.addClassName("queue-" + job.status().name().toLowerCase(Locale.ROOT));
        row.getElement().setAttribute("role", "listitem");

        Div body = new Div();
        body.addClassName("queue-body");

        Div titleLine = new Div();
        titleLine.addClassName("queue-title-line");
        Span title = new Span(job.title);
        title.addClassName("queue-book");
        titleLine.add(title);
        boolean replaced = job.status() == AudiobookJobs.State.DONE && reveal.replaced(job);
        Span state = new Span(replaced ? "Replaced" : stateLabel(job));
        state.addClassName("queue-state");
        state.addClassName(
            replaced ? "state-cancelled" : "state-" + job.status().name().toLowerCase(Locale.ROOT)
        );
        if (replaced) {
            state
                .getElement()
                .setAttribute("title", "A newer build of this audiobook is the current download");
        }
        titleLine.add(state);
        body.add(titleLine);

        Span meta = new Span(metaLine(job));
        meta.addClassName("queue-meta");
        meta.getElement().setAttribute("title", job.step == null ? "" : job.step);
        body.add(meta);

        if (
            job.status() == AudiobookJobs.State.FAILED && job.error != null && !job.error.isBlank()
        ) {
            Span error = new Span(job.error);
            error.addClassName("queue-error");
            body.add(error);
        }

        Div actions = new Div();
        actions.addClassName("queue-actions");
        switch (job.status()) {
            case RUNNING -> {
                Button cancel = new Button("Cancel", event -> confirmCancel(job));
                cancel.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                cancel.addClassName("queue-cancel");
                cancel.getElement().setAttribute("aria-label", "Cancel conversion of " + job.title);
                cancel.setEnabled(!"Cancelling…".equals(job.step));
                actions.add(cancel);
            }
            case QUEUED -> {
                Button cancel = new Button("Cancel", event -> {
                    jobs.cancel(job.id);
                    reveal.changed();
                });
                cancel.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                cancel.addClassName("queue-cancel");
                cancel.getElement().setAttribute("aria-label", "Cancel conversion of " + job.title);
                actions.add(cancel);
            }
            case DONE -> {
                if (replaced) {
                    row.addClassName("queue-replaced");
                }
                Anchor download = new Anchor(
                    AppUrls.relative("api/tracks/" + job.resultTrackId + "/file/m4b?download=1"),
                    replaced ? "Download" : "Download M4B"
                );
                download.addClassName(replaced ? "queue-download-secondary" : "queue-download");
                download.getElement().setAttribute("download", job.resultFileName);
                download
                    .getElement()
                    .setAttribute(
                        "title",
                        "Download " +
                            job.resultFileName +
                            (job.resultSizeBytes > 0 ? " · " + Fmt.bytes(job.resultSizeBytes) : "")
                    );
                Button show = new Button("Show in library", event -> reveal.showInLibrary(job));
                show.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                show.addClassName("queue-show");
                show.getElement().setAttribute("title", "Scroll to this audiobook in the library");
                actions.add(download, show);
                actions.add(dismiss(job));
            }
            case FAILED, CANCELLED -> {
                Button retry = new Button("Try again…", event -> reveal.retry(job));
                retry.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                retry.addClassName("queue-retry");
                retry
                    .getElement()
                    .setAttribute("title", "Open the audiobook dialog for these recordings again");
                actions.add(retry, dismiss(job));
            }
        }

        row.add(body, actions);
        if (job.status() == AudiobookJobs.State.RUNNING) {
            Div track = new Div();
            track.addClassName("queue-progress");
            track.getElement().setAttribute("role", "progressbar");
            track.getElement().setAttribute("aria-valuemin", "0");
            track.getElement().setAttribute("aria-valuemax", "100");
            track
                .getElement()
                .setAttribute("aria-valuenow", String.valueOf(Math.round(job.progress * 100)));
            track.getElement().setAttribute("aria-label", "Converting " + job.title);
            Div bar = new Div();
            bar.addClassName("queue-progress-bar");
            bar.getStyle().set("width", percent(job.progress));
            track.add(bar);
            row.add(track);
        }
        return row;
    }

    private void confirmCancel(AudiobookJobs.Job job) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Cancel this conversion?");
        dialog.setText(
            "\u201c" +
                job.title +
                "\u201d stops now and its progress is lost. " +
                "The source recordings stay in the library."
        );
        dialog.setCancelable(true);
        dialog.setCancelText("Keep converting");
        dialog.setConfirmText("Cancel conversion");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            jobs.cancel(job.id);
            reveal.changed();
        });
        dialog.open();
    }

    private Button dismiss(AudiobookJobs.Job job) {
        Button dismiss = new Button(VaadinIcon.CLOSE_SMALL.create(), event -> {
            jobs.remove(job.id);
            reveal.changed();
        });
        dismiss.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        dismiss.addClassName("queue-dismiss");
        dismiss.getElement().setAttribute("aria-label", "Remove " + job.title + " from the list");
        dismiss.getElement().setAttribute("title", "Remove from this list");
        return dismiss;
    }

    /** How long a newly queued row stays highlighted. */
    private static final long FRESH_MS = 4000;
    /** Finished rows shown before the rest are summarised, so the card stays compact. */
    private static final int VISIBLE_FINISHED = 3;

    /** DOM id of one queue row, so a start action can highlight it. */
    static String jobAnchor(String jobId) {
        return "app-job-" + jobId;
    }

    private static String stateLabel(AudiobookJobs.Job job) {
        return switch (job.status()) {
            case QUEUED -> "Queued";
            case RUNNING -> Math.round(job.progress * 100) + "%";
            case DONE -> "Done";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static String metaLine(AudiobookJobs.Job job) {
        String author = job.author == null || job.author.isBlank() ? "" : job.author;
        return switch (job.status()) {
            case QUEUED -> join(author, "Waiting for the current conversion");
            case RUNNING -> join(author, job.step == null ? "Converting" : job.step);
            case DONE -> join(
                finishedAt(job),
                job.chapterCount > 0 ? chapters(job.chapterCount) : "",
                job.durationMs > 0 ? Fmt.duration(job.durationMs) : "",
                job.resultFileName == null ? "" : job.resultFileName
            );
            case FAILED -> join(
                finishedAt(job),
                author,
                job.step == null ? "Conversion failed" : job.step
            );
            case CANCELLED -> join(finishedAt(job), author, "source recordings are unchanged");
        };
    }

    private static String finishedAt(AudiobookJobs.Job job) {
        return job.finishedAtEpochMs <= 0 ? "" : Fmt.dateTime(job.finishedAtEpochMs);
    }

    private static String chapters(int count) {
        return count + (count == 1 ? " chapter" : " chapters");
    }

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", Math.max(0, Math.min(1, fraction)) * 100);
    }

    /** Scrolls the panel into view, used right after a conversion was started. */
    void scrollIntoView() {
        getElement().executeJs("this.scrollIntoView({block:'nearest', behavior:'smooth'})");
    }
}
