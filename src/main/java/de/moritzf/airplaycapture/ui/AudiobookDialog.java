package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.config.SettingsService;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.media.ChapterCatalog;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** User-controlled audiobook assembly: sources, metadata lookup and chapter options. */
public final class AudiobookDialog extends Dialog {

    private static final String[] SOURCE_FORMATS = { "alac", "m4a", "mp3", "caf" };
    private static final String PREVIEW_PLAY_JS = """
    const audio = document.getElementById('app-book-preview');
    if (audio) {
        audio.src = this.dataset.src;
        audio.play();
    }
    """;

    private final Library library;
    private final SettingsService settingsService;
    private final AudiobookCreator creator;
    private final AudiobookJobs jobs;
    private final LibraryGroups.Group group;
    private final java.util.function.Consumer<AudiobookJobs.Job> onSubmitted;

    private final CheckboxGroup<LibraryTrack> sources = new CheckboxGroup<>();
    private final TextField title = new TextField("Title");
    private final TextField author = new TextField("Author");
    private final ComboBox<AudiobookCreator.ChapterMode> chapterMode = new ComboBox<>("Chapters");
    private final ComboBox<Integer> bitrate = new ComboBox<>("Audiobook bitrate");
    private final Span lookupState = new Span();
    private final Span chapterState = new Span();
    private final Button changeEdition = new Button("Change");
    private final Span duplicateNote = new Span();
    private final VerticalLayout content = new VerticalLayout();
    private final List<LibraryTrack> sourceOrder = new ArrayList<>();
    private final Div chapterList = new Div();
    private final Span previewState = new Span();
    private final Button previewButton = new Button("Preview chapter cuts");
    private final Div previewAudioSlot = new Div();
    private final ProgressBar previewProgress = new ProgressBar();
    private String previewId;
    private int previewToken;
    private int catalogToken;
    private ChapterCatalog.Result selectedCatalog;
    private boolean submitted;

    public AudiobookDialog(
        Library library,
        SettingsService settingsService,
        AudiobookCreator creator,
        AudiobookJobs jobs,
        LibraryGroups.Group group,
        java.util.function.Consumer<AudiobookJobs.Job> onSubmitted
    ) {
        this.library = library;
        this.settingsService = settingsService;
        this.creator = creator;
        this.jobs = jobs;
        this.group = group;
        this.onSubmitted = onSubmitted;

        addClassName("book-dialog");
        setHeaderTitle(group.hasM4b() ? "Rebuild audiobook" : "Create audiobook");
        setWidth("min(680px, 94vw)");

        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        content.getStyle().set("max-height", "min(60vh, 640px)");
        content.getStyle().set("overflow-y", "auto");
        add(content);

        Settings settings = settingsService.current();
        buildSources();
        buildMetadata();
        buildOptions(settings);
        buildPreview();

        Button cancel = new Button("Cancel", event -> close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button create = new Button(
            group.hasM4b() ? "Create another audiobook" : "Create audiobook",
            event -> create()
        );
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(cancel, create);
        getFooter().getElement().getStyle().set("flex-wrap", "wrap");
        getFooter().getElement().getStyle().set("gap", "8px");

        Button close = new Button(VaadinIcon.CLOSE.create(), event -> close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        close.addClassName("dialog-close");
        close.getElement().setAttribute("aria-label", "Close dialog");
        getHeader().add(close);
        getHeader().getElement().getStyle().set("align-items", "center");
        getHeader().getElement().getStyle().set("gap", "8px");
        addDetachListener(event -> {
            if (!submitted) {
                creator.discard(previewId);
            }
        });

        title.setRequiredIndicatorVisible(true);
        title.setValue(group.title() == null ? "" : group.title());
        author.setValue("Various artists".equals(group.artist()) ? "" : nvl(group.artist()));
        lookupChapters();
    }

    private void buildSources() {
        List<LibraryTrack> playable = new ArrayList<>();
        for (LibraryTrack track : group.tracks()) {
            if (track.chapterCount == null && hasSource(track)) {
                playable.add(track);
            }
        }
        sources.setWidthFull();
        sources.setItems(playable);
        sourceOrder.clear();
        sourceOrder.addAll(playable);
        sources.setItemLabelGenerator(this::sourceLabel);
        Set<LibraryTrack> best = new LinkedHashSet<>(group.bestTakes());
        sources.select(
            playable
                .stream()
                .filter(best::contains)
                .toArray(LibraryTrack[]::new)
        );
        sources.addValueChangeListener(event -> {
            updateDuplicateNote();
            invalidatePreview();
        });

        H3 heading = new H3("Recordings");
        heading.addClassName("dialog-heading");
        HorizontalLayout headingRow = new HorizontalLayout(heading);
        headingRow.setWidthFull();
        headingRow.setAlignItems(
            com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER
        );
        headingRow.setJustifyContentMode(
            com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode.BETWEEN
        );
        if (playable.size() > 1) {
            Button selectAll = new Button("Select all", event ->
                sources.setValue(new LinkedHashSet<>(playable))
            );
            Button clear = new Button("Clear", event -> sources.clear());
            selectAll.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            clear.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            HorizontalLayout selection = new HorizontalLayout(selectAll, clear);
            selection.setSpacing(false);
            headingRow.add(selection);
        }

        Span note = new Span(
            "Duplicate takes stay separate. Best takes are preselected; " +
                "uncheck anything that should not go into the book."
        );
        note.addClassName("dialog-note");
        duplicateNote.addClassName("dialog-note");
        content.add(headingRow, note, sources, duplicateNote);
        updateDuplicateNote();

        if (playable.size() < group.tracks().size()) {
            int hidden = group.tracks().size() - playable.size();
            Span skipped = new Span(
                hidden +
                    (hidden == 1 ? " recording is" : " recordings are") +
                    " an existing audiobook or have no source file and are hidden."
            );
            skipped.addClassName("dialog-note");
            content.add(skipped);
        }
    }

    private void updateDuplicateNote() {
        Set<LibraryTrack> selected = sources.getValue();
        if (selected == null || selected.isEmpty()) {
            duplicateNote.setText("");
            return;
        }
        List<LibraryTrack> chosen = new ArrayList<>(selected);
        List<LibraryTrack> deduped = LibraryGroups.bestTakes(chosen);
        if (deduped.size() < chosen.size()) {
            duplicateNote.setText(
                "Duplicate takes selected: only the best take per title " +
                    "will be used for the audiobook."
            );
        } else {
            duplicateNote.setText("");
        }
    }

    private void buildMetadata() {
        H3 heading = new H3("Metadata");
        heading.addClassName("dialog-heading");
        title.setWidthFull();
        author.setWidthFull();
        lookupState.addClassName("dialog-note");

        Button find = new Button("Find editions", VaadinIcon.SEARCH.create(), event ->
            openPicker()
        );
        find.addThemeVariants(ButtonVariant.LUMO_SMALL);
        lookupState.setText("Searches Apple Books, Deezer and MusicBrainz.");
        HorizontalLayout lookupRow = new HorizontalLayout(find, lookupState);
        lookupRow.setAlignItems(
            com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER
        );
        lookupRow.setSpacing(true);

        VerticalLayout fields = new VerticalLayout(title, author, lookupRow);
        fields.setPadding(false);
        fields.setSpacing(true);
        fields.setWidthFull();
        content.add(heading, fields);
    }

    private void buildOptions(Settings settings) {
        H3 heading = new H3("Chapters and output");
        heading.addClassName("dialog-heading");

        chapterMode.setWidthFull();
        bitrate.setWidthFull();
        long playableCount = sources.getListDataView().getItems().count();
        chapterMode.setItems(AudiobookCreator.ChapterMode.values());
        chapterMode.setItemLabelGenerator(
            mode ->
                switch (mode) {
                    case CATALOG -> "From catalog · chapter names and markers";
                    case PAUSES -> "At pauses · detect silence";
                    case RECORDINGS -> "One chapter per recording";
                }
        );
        chapterMode.setValue(
            settings.audiobookCatalogChapters
                ? AudiobookCreator.ChapterMode.CATALOG
                : settings.audiobookSplitLongTracks && playableCount <= 1
                  ? AudiobookCreator.ChapterMode.PAUSES
                  : AudiobookCreator.ChapterMode.RECORDINGS
        );
        chapterMode.addValueChangeListener(event -> invalidatePreview());
        chapterMode.setHelperText(
            "Catalog uses chapter names and durations from Deezer/MusicBrainz " +
                "and aligns the cuts to pauses. Pauses splits at silence of at least 2.5 s with " +
                "at least 5 minutes per chapter. One chapter per recording keeps every selected " +
                "recording whole. Catalog falls back to pauses when no matching edition is found."
        );
        chapterState.addClassName("dialog-note");
        chapterState.getElement().setAttribute("aria-live", "polite");
        if (settings.audiobookCatalogChapters) {
            chapterState.setText("Looking for catalog chapters…");
        } else {
            chapterState.setText("Catalog chapter lists are disabled in Settings.");
        }
        changeEdition.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        changeEdition.addClickListener(event -> openPicker());
        HorizontalLayout chapterRow = new HorizontalLayout(chapterState, changeEdition);
        chapterRow.setWidthFull();
        chapterRow.setSpacing(false);
        chapterRow.setAlignItems(
            com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER
        );
        chapterRow.setFlexGrow(1, chapterState);

        bitrate.setItems(List.of(32, 48, 64, 80, 96, 128, 160, 192));
        bitrate.setItemLabelGenerator(
            kbps ->
                switch (kbps) {
                    case 32 -> "32 kbps · voice";
                    case 48 -> "48 kbps · voice";
                    case 64 -> "64 kbps · compact";
                    case 80 -> "80 kbps";
                    case 96 -> "96 kbps · recommended";
                    case 128 -> "128 kbps · higher";
                    case 160 -> "160 kbps";
                    default -> "192 kbps · maximum";
                }
        );
        selectBitrate(settings.audiobookBitrateKbps);

        Span note = new Span(
            "Uses the best available file per recording " +
                "(ALAC > M4A > MP3 > CAF). Source recordings are never deleted. " +
                "M4A sources are re-encoded once, so prefer ALAC in Settings for perfect quality."
        );
        note.addClassName("dialog-note");
        if (group.hasM4b()) {
            Span rebuild = new Span(
                "This group already has an audiobook. Creating another one " +
                    "keeps the existing .m4b and adds a second file."
            );
            rebuild.addClassName("dialog-note");
            content.add(heading, chapterMode, chapterRow, bitrate, rebuild, note);
        } else {
            content.add(heading, chapterMode, chapterRow, bitrate, note);
        }
    }

    private void buildPreview() {
        H3 heading = new H3("Preview");
        heading.addClassName("dialog-heading");
        previewButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        previewButton.addClickListener(event -> preview());
        previewProgress.setIndeterminate(true);
        previewProgress.setWidth("140px");
        previewProgress.setVisible(false);
        previewProgress.getElement().setAttribute("aria-label", "Analyzing chapters");
        previewState.addClassName("dialog-note");
        previewState.getElement().setAttribute("aria-live", "polite");
        previewState.setText("Analyze the chapter plan before creating the .m4b.");
        chapterList.addClassName("preview-list");
        chapterList.setWidthFull();
        chapterList.getStyle().set("max-height", "240px");
        chapterList.getStyle().set("overflow-y", "auto");

        Element audio = new Element("audio");
        audio.setAttribute("controls", "");
        audio.setAttribute("preload", "none");
        audio.setAttribute("id", "app-book-preview");
        audio.setAttribute("aria-label", "Chapter preview player");
        previewAudioSlot.addClassName("preview-audio");
        previewAudioSlot.getElement().appendChild(audio);
        previewAudioSlot.setVisible(false);

        HorizontalLayout controls = new HorizontalLayout(previewButton, previewProgress);
        controls.setAlignItems(
            com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER
        );
        controls.setSpacing(true);
        content.add(heading, controls, previewState, previewAudioSlot, chapterList);
    }

    private void preview() {
        Set<LibraryTrack> selected = sources.getValue();
        if (selected == null || selected.isEmpty()) {
            Notification.show("Select at least one recording", 3000, Notification.Position.MIDDLE);
            return;
        }
        invalidatePreview();
        int token = ++previewToken;
        previewButton.setEnabled(false);
        previewProgress.setVisible(true);
        previewState.setText(
            switch (chapterMode.getValue()) {
                case CATALOG -> "Looking up catalog chapters and pauses…";
                case PAUSES -> "Analyzing pauses…";
                case RECORDINGS -> "Preparing chapters…";
            }
        );
        AudiobookCreator.Request request = request(selected);
        UI ui = UI.getCurrent();
        Thread.ofVirtual()
            .name("book-preview")
            .start(() -> {
                try {
                    AudiobookCreator.Preview result = creator.analyze(request);
                    if (ui == null) {
                        creator.discard(result.id());
                        return;
                    }
                    try {
                        ui.access(() -> {
                            if (token != previewToken) {
                                creator.discard(result.id());
                                return;
                            }
                            previewButton.setEnabled(true);
                            previewProgress.setVisible(false);
                            showPreview(result);
                        });
                    } catch (RuntimeException e) {
                        creator.discard(result.id());
                    }
                } catch (Exception e) {
                    if (ui == null) {
                        return;
                    }
                    try {
                        ui.access(() -> {
                            if (token == previewToken) {
                                previewButton.setEnabled(true);
                                previewProgress.setVisible(false);
                                previewState.setText(
                                    "Preview failed: " + shortError(e.getMessage())
                                );
                            }
                        });
                    } catch (RuntimeException ignored) {
                        // dialog was closed while analyzing
                    }
                }
            });
    }

    private void showPreview(AudiobookCreator.Preview result) {
        previewId = result.id();
        chapterList.removeAll();
        for (AudiobookCreator.ChapterPreview chapter : result.chapters()) {
            String audio = AppUrls.relative(
                "api/audiobook-preview/" + result.id() + "/" + chapter.index()
            );
            Div row = new Div();
            row.addClassName("preview-row");
            Span index = new Span(chapter.index() + 1 + ".");
            index.addClassName("preview-index");
            Div text = new Div();
            text.addClassName("preview-text");
            Span label = new Span(chapter.title());
            label.addClassName("preview-title");
            Span meta = new Span(
                Fmt.duration(chapter.durationMs()) + " · starts " + Fmt.duration(chapter.startMs())
            );
            meta.addClassName("preview-meta");
            text.add(label, meta);

            Button sample = new Button("Sample");
            sample.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            sample.addClassName("row-action");
            sample.addClassName("preview-sample");
            sample.getElement().setAttribute("title", "Play first 10 s and last 8 s");
            sample
                .getElement()
                .setAttribute(
                    "aria-label",
                    "Play boundary sample for chapter " + (chapter.index() + 1)
                );
            sample.getElement().setAttribute("data-src", audio);
            sample.getElement().setAttribute("onclick", PREVIEW_PLAY_JS);

            Button full = new Button("Full");
            full.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            full.addClassName("row-action");
            full.addClassName("preview-full");
            full.getElement().setAttribute("title", "Play the whole chapter");
            full.getElement().setAttribute(
                "aria-label",
                "Play full chapter " + (chapter.index() + 1)
            );
            full.getElement().setAttribute("data-src", audio + "/full");
            full.getElement().setAttribute("onclick", PREVIEW_PLAY_JS);
            row.add(index, text, sample, full);
            chapterList.add(row);
        }
        String source = switch (result.source() == null ? "" : result.source()) {
            case "pauses" -> "cuts at pauses";
            case "recordings" -> "one chapter per recording";
            default -> "chapters from " + result.source();
        };
        String note = result.note() == null || result.note().isBlank() ? "" : " · " + result.note();
        previewState.setText(
            Fmt.duration(result.durationMs()) +
                " · " +
                result.chapters().size() +
                " chapter" +
                (result.chapters().size() == 1 ? "" : "s") +
                " · " +
                source +
                note +
                " · Sample plays the start and end, Full plays the whole chapter."
        );
        previewAudioSlot.setVisible(true);
        chapterList.getElement().executeJs("this.scrollIntoView({block:'nearest'})");
    }

    private void invalidatePreview() {
        previewToken++;
        if (previewId != null) {
            creator.discard(previewId);
            previewId = null;
        }
        chapterList.removeAll();
        previewState.setText("");
        previewButton.setEnabled(true);
        previewProgress.setVisible(false);
        previewAudioSlot.setVisible(false);
    }

    private AudiobookCreator.Request request(Set<LibraryTrack> selected) {
        List<LibraryTrack> ordered = sourceOrder.stream().filter(selected::contains).toList();
        return new AudiobookCreator.Request(
            group.key(),
            nvl(title.getValue()).trim(),
            nvl(group.title()).trim(),
            nvl(author.getValue()).trim(),
            ordered,
            chapterMode.getValue() == null
                ? AudiobookCreator.ChapterMode.PAUSES
                : chapterMode.getValue(),
            bitrate.getValue() == null ? 96 : bitrate.getValue(),
            selectedCatalog
        );
    }

    private String sourceLabel(LibraryTrack track) {
        LibraryGroups.Take take = LibraryGroups.take(group.tracks(), track);
        StringBuilder label = new StringBuilder();
        label.append(sourceOrder.indexOf(track) + 1).append(". ");
        label.append(
            track.title == null || track.title.isBlank() ? track.displayName() : track.title
        );
        if (track.artist != null && !track.artist.isBlank() && !track.artist.equals(track.title)) {
            label.append(" · ").append(track.artist);
        }
        label.append(" · ").append(Fmt.duration(track.durationMs));
        if (track.gaps > 0) {
            label
                .append(" · ")
                .append(track.gaps)
                .append(track.gaps == 1 ? " gap" : " gaps");
        }
        if (track.incomplete) {
            label.append(" · incomplete");
        }
        if (take != null) {
            label.append(take.best() ? " · best take" : " · take " + take.index());
        }
        return label.toString();
    }

    private void openPicker() {
        String currentTitle = isBlank(group.title()) ? title.getValue() : group.title();
        String currentAuthor =
            isBlank(group.artist()) || "Various artists".equals(group.artist())
                ? author.getValue()
                : group.artist();
        CatalogPickerDialog picker = new CatalogPickerDialog(
            creator,
            currentTitle,
            currentAuthor,
            selectedDurationMs(),
            this::applyEdition
        );
        picker.open();
    }

    private void applyEdition(AudiobookCreator.CatalogMatch match) {
        catalogToken++;
        if (!isBlank(match.title())) {
            title.setValue(match.title());
        }
        if (!isBlank(match.author())) {
            author.setValue(match.author());
        }
        selectedCatalog = match.chapters();
        lookupState.setText("Selected " + match.source() + " edition.");
        if (match.hasChapters()) {
            chapterMode.setValue(AudiobookCreator.ChapterMode.CATALOG);
            String fit = match.fits(selectedDurationMs())
                ? ""
                : " · does not fit this recording; preview will fall back to pauses";
            chapterState.setText(
                match.chapterCount() +
                    " chapters from " +
                    match.source() +
                    " · " +
                    Fmt.duration(match.durationMs()) +
                    " · " +
                    match.title() +
                    fit
            );
        } else {
            chapterMode.setValue(AudiobookCreator.ChapterMode.PAUSES);
            chapterState.setText("Apple Books has no chapter list; pauses will be used.");
        }
        invalidatePreview();
    }

    /** Looks up chapters in the background when the dialog opens; never overwrites title/author. */
    private void lookupChapters() {
        if (!settingsService.current().audiobookCatalogChapters) {
            chapterState.setText("Catalog chapter lists are disabled in Settings.");
            return;
        }
        int token = ++catalogToken;
        UI ui = UI.getCurrent();
        String album = group.title();
        String currentTitle = title.getValue();
        String currentAuthor = author.getValue();
        long expected = selectedDurationMs();
        Thread.ofVirtual()
            .name("book-chapters")
            .start(() -> {
                Optional<ChapterCatalog.Result> found = creator.lookupChapters(
                    currentTitle,
                    album,
                    currentAuthor,
                    expected
                );
                if (ui == null) {
                    return;
                }
                try {
                    ui.access(() -> {
                        if (token == catalogToken && selectedCatalog == null) {
                            showCatalog(found);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // dialog was closed while looking up
                }
            });
    }

    private void showCatalog(Optional<ChapterCatalog.Result> found) {
        if (found.isEmpty()) {
            chapterState.setText(
                "No catalog chapter list found. Pauses can still split the recording."
            );
            return;
        }
        ChapterCatalog.Result result = found.get();
        chapterState.setText(
            result.chapters().size() +
                " chapters from " +
                result.source() +
                " · " +
                Fmt.duration(result.totalMs()) +
                " · " +
                result.album()
        );
    }

    private long selectedDurationMs() {
        Set<LibraryTrack> selected = sources.getValue();
        if (selected == null || selected.isEmpty()) {
            return 0;
        }
        List<LibraryTrack> chosen = sourceOrder.stream().filter(selected::contains).toList();
        return LibraryGroups.bestTakes(chosen)
            .stream()
            .mapToLong(track -> track.durationMs)
            .sum();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void create() {
        Set<LibraryTrack> selected = sources.getValue();
        if (selected == null || selected.isEmpty()) {
            Notification.show("Select at least one recording", 3000, Notification.Position.MIDDLE);
            return;
        }
        if (title.getValue() == null || title.getValue().isBlank()) {
            Notification.show("Title is required", 3000, Notification.Position.MIDDLE);
            return;
        }
        AudiobookCreator.Request request = request(selected);
        submitted = true;
        String preview = previewId;
        previewId = null;
        AudiobookJobs.Job job = jobs.enqueue(
            group.key(),
            request.title(),
            request.author(),
            progress ->
                preview != null
                    ? creator.convert(
                          preview,
                          request.title(),
                          request.author(),
                          request.bitrateKbps(),
                          progress
                      )
                    : creator.convert(request, progress)
        );
        close();
        onSubmitted.accept(job);
    }

    private boolean hasSource(LibraryTrack track) {
        for (String format : SOURCE_FORMATS) {
            Path path = library.file(track, format);
            if (path != null && Files.exists(path)) {
                return true;
            }
        }
        return false;
    }

    private void selectBitrate(int kbps) {
        List<Integer> values = new ArrayList<>(bitrate.getListDataView().getItems().toList());
        if (!values.contains(kbps)) {
            values.add(kbps);
            values.sort(Integer::compareTo);
            bitrate.setItems(values);
        }
        bitrate.setValue(kbps);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String shortError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String value = message.replaceAll("\\s+", " ").trim();
        return value.length() > 180 ? value.substring(0, 180) + "…" : value;
    }
}
