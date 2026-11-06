package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextField;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Catalog edition picker: shows every plausible Apple Books, Deezer and MusicBrainz match with
 * cover, duration and chapter count, and lets the user choose the edition that should be used.
 */
public final class CatalogPickerDialog extends Dialog {

    private final AudiobookCreator creator;
    private final long recordingMs;
    private final Consumer<AudiobookCreator.CatalogMatch> onSelect;
    private final TextField searchTitle = new TextField("Title");
    private final TextField searchAuthor = new TextField("Author");
    private final Div results = new Div();
    private final Span state = new Span();
    private final ProgressBar progress = new ProgressBar();
    private final Button use = new Button("Use edition");
    private final List<Div> cards = new ArrayList<>();
    private AudiobookCreator.CatalogMatch selected;
    private int searchToken;

    public CatalogPickerDialog(
        AudiobookCreator creator,
        String title,
        String author,
        long recordingMs,
        Consumer<AudiobookCreator.CatalogMatch> onSelect
    ) {
        this.creator = creator;
        this.recordingMs = recordingMs;
        this.onSelect = onSelect;

        addClassName("catalog-picker");
        setHeaderTitle("Choose an edition");
        setWidth("min(760px, 94vw)");

        searchTitle.setValue(nvl(title));
        searchAuthor.setValue(nvl(author));
        searchTitle.setWidthFull();
        searchAuthor.setWidthFull();

        Button search = new Button("Search", VaadinIcon.SEARCH.create(), event -> search());
        search.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        searchTitle.addKeyPressListener(Key.ENTER, event -> search());
        searchAuthor.addKeyPressListener(Key.ENTER, event -> search());

        HorizontalLayout fields = new HorizontalLayout(searchTitle, searchAuthor, search);
        fields.setWidthFull();
        fields.setWrap(true);
        fields.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        fields.setFlexGrow(1, searchTitle, searchAuthor);

        Span recording = new Span(
            recordingMs > 0 ? "Recording: " + Fmt.duration(recordingMs) : "Recording length unknown"
        );
        recording.addClassName("dialog-note");
        state.addClassName("dialog-note");
        state.getElement().setAttribute("aria-live", "polite");
        progress.setIndeterminate(true);
        progress.setWidth("180px");
        progress.setVisible(false);
        progress.getElement().setAttribute("aria-label", "Searching catalogs");

        results.addClassName("catalog-results");
        results.setWidthFull();

        VerticalLayout content = new VerticalLayout(fields, recording, state, progress, results);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        content.getStyle().set("max-height", "min(62vh, 620px)");
        content.getStyle().set("overflow-y", "auto");
        add(content);

        Button cancel = new Button("Cancel", event -> close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        use.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        use.setEnabled(false);
        use.addClickListener(event -> {
            if (selected != null) {
                onSelect.accept(selected);
                close();
            }
        });
        getFooter().add(cancel, use);

        Button close = new Button(VaadinIcon.CLOSE.create(), event -> close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        close.addClassName("dialog-close");
        close.getElement().setAttribute("aria-label", "Close edition picker");
        getHeader().add(close);

        search();
    }

    private void search() {
        String title = nvl(searchTitle.getValue()).trim();
        String author = nvl(searchAuthor.getValue()).trim();
        if (title.isBlank()) {
            state.setText("Enter a title to search.");
            return;
        }
        int token = ++searchToken;
        selected = null;
        use.setEnabled(false);
        use.setText("Use edition");
        cards.clear();
        results.removeAll();
        progress.setVisible(true);
        state.setText("Searching Apple Books, Deezer and MusicBrainz…");
        UI ui = UI.getCurrent();
        Thread.ofVirtual()
            .name("catalog-picker")
            .start(() -> {
                List<AudiobookCreator.CatalogMatch> matches = creator.searchCatalog(
                    title,
                    null,
                    author,
                    recordingMs
                );
                if (ui == null) {
                    return;
                }
                try {
                    ui.access(() -> {
                        if (token == searchToken) {
                            progress.setVisible(false);
                            show(matches);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // picker was closed while searching
                }
            });
    }

    private void show(List<AudiobookCreator.CatalogMatch> matches) {
        results.removeAll();
        cards.clear();
        if (matches.isEmpty()) {
            Div empty = new Div("No editions found. Try a different title or author.");
            empty.addClassName("catalog-empty");
            results.add(empty);
            state.setText("No matches.");
            return;
        }
        boolean anyFit = matches
            .stream()
            .anyMatch(match -> match.hasChapters() && match.fits(recordingMs));
        boolean recommended = false;
        for (AudiobookCreator.CatalogMatch match : matches) {
            boolean best =
                !recommended && match.hasChapters() && (!anyFit || match.fits(recordingMs));
            if (best) {
                recommended = true;
            }
            Div card = card(match, best, anyFit);
            cards.add(card);
            results.add(card);
        }
        state.setText(
            matches.size() +
                " editions · recording " +
                Fmt.duration(recordingMs) +
                " · sorted by length fit; pick the edition whose chapters you want."
        );
    }

    private Div card(AudiobookCreator.CatalogMatch match, boolean recommended, boolean anyFit) {
        Div card = new Div();
        card.addClassName("catalog-match");
        card.getElement().setAttribute("role", "radio");
        card.getElement().setAttribute("tabindex", "0");
        card.getElement().setAttribute("aria-checked", "false");

        Div cover = new Div();
        cover.addClassName("catalog-cover");
        if (!isBlank(match.coverUrl())) {
            Image image = new Image(match.coverUrl(), "Cover");
            image.addClassName("catalog-cover-image");
            image.getElement().setAttribute("loading", "lazy");
            image.getElement().setAttribute("onerror", "this.style.display='none'");
            cover.add(image);
        } else {
            cover.add(VaadinIcon.BOOK.create());
            cover.addClassName("catalog-cover-placeholder");
        }
        card.add(cover);

        Div text = new Div();
        text.addClassName("catalog-text");
        Span title = new Span(match.title());
        title.addClassName("catalog-title");
        Span meta = new Span(meta(match));
        meta.addClassName("catalog-meta");
        Div badges = new Div();
        badges.addClassName("catalog-badges");
        badges.add(badge(match.source(), "source"));
        if (match.hasChapters()) {
            badges.add(badge(match.chapterCount() + " chapters", "chapters"));
        } else {
            badges.add(badge("metadata only", "muted"));
        }
        if (match.durationMs() > 0) {
            badges.add(badge(Fmt.duration(match.durationMs()), "duration"));
        }
        if (match.hasChapters() && recordingMs > 0) {
            badges.add(
                match.fits(recordingMs)
                    ? badge("fits recording", "ok")
                    : badge("different length", "warning")
            );
        }
        if (recommended) {
            badges.add(badge(anyFit ? "Best length match" : "Closest length", "recommended"));
        }
        text.add(title, meta, badges);
        card.add(text);

        Span check = new Span(VaadinIcon.CHECK.create());
        check.addClassName("catalog-check");
        card.add(check);

        card.addClickListener(event -> select(card, match));
        card.getElement()
            .addEventListener("keydown", event -> select(card, match))
            .setFilter("event.key === 'Enter' || event.key === ' '");
        return card;
    }

    private void select(Div card, AudiobookCreator.CatalogMatch match) {
        selected = match;
        for (Div other : cards) {
            other.removeClassName("selected");
            other.getElement().setAttribute("aria-checked", "false");
        }
        card.addClassName("selected");
        card.getElement().setAttribute("aria-checked", "true");
        use.setEnabled(true);
        use.setText(
            !match.hasChapters()
                ? "Use metadata"
                : match.fits(recordingMs)
                  ? "Use edition"
                  : "Use anyway"
        );
    }

    private static String meta(AudiobookCreator.CatalogMatch match) {
        StringBuilder meta = new StringBuilder();
        if (!isBlank(match.author())) {
            meta.append(match.author());
        }
        if (match.year() != null) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(match.year());
        }
        return meta.length() == 0 ? "Unknown author" : meta.toString();
    }

    private static Span badge(String text, String kind) {
        Span badge = new Span(text);
        badge.addClassName("catalog-badge");
        badge.addClassName(kind);
        return badge;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
