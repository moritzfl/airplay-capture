package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider.HierarchyFormat;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.config.SettingsService;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryGroups;
import de.moritzf.airplaycapture.library.LibraryTrack;
import de.moritzf.airplaycapture.recording.AudiobookCreator;
import de.moritzf.airplaycapture.recording.AudiobookJobs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Live recording status plus the searchable library. */
@Route(value = "", layout = MainLayout.class)
public class LibraryView extends VerticalLayout {

    private static final String[] FORMAT_ORDER = { "m4b", "m4a", "mp3", "alac", "caf" };

    /** One grid row: either a group header ({@code track == null}) or a recording. */
    private record Row(String key, LibraryGroups.Group group, LibraryTrack track) {
        boolean groupRow() {
            return track == null;
        }
    }

    private final Library library;
    private final UiState state;
    private final UiBroadcaster broadcaster;
    private final SettingsService settingsService;
    private final AudiobookCreator audiobookCreator;
    private final AudiobookJobs audiobookJobs;
    private final AudiobookQueuePanel queuePanel;

    private final Div nowCard = new Div();
    private final Div nowCover = new Div();
    private final Div nowCoverPlaceholder = new Div();
    private final Image nowCoverImage = new Image();
    private final Span nowEyebrow = new Span();
    private final H2 nowTitle = new H2();
    private final Span nowSub = new Span();
    private final Div progressBar = new Div();
    private final Span nowTime = new Span();
    private final Span nowClient = new Span();

    private final TreeGrid<Row> grid = new TreeGrid<>();
    private final Div cardList = new Div();
    private final Div emptyState = new Div();
    private final TextField search = new TextField();
    private final Span trackCount = new Span();
    private final Span groupCount = new Span();
    private final Span totalSize = new Span();
    private final AudioPlayer player = new AudioPlayer();
    private final Map<String, LibraryGroups.Take> takeInfo = new HashMap<>();
    private final Set<String> expandedGroups = new HashSet<>();
    private final Set<String> expandedCards = new HashSet<>();

    private Registration registration;
    private Registration resizeRegistration;
    private String gridSignature = "";
    private long shownCoverVersion = -1;

    public LibraryView(
        Library library,
        UiState state,
        UiBroadcaster broadcaster,
        SettingsService settingsService,
        AudiobookCreator audiobookCreator,
        AudiobookJobs audiobookJobs
    ) {
        this.library = library;
        this.state = state;
        this.broadcaster = broadcaster;
        this.settingsService = settingsService;
        this.audiobookCreator = audiobookCreator;
        this.audiobookJobs = audiobookJobs;
        this.queuePanel = new AudiobookQueuePanel(
            audiobookJobs,
            new AudiobookQueuePanel.Reveal() {
                @Override
                public void showInLibrary(AudiobookJobs.Job job) {
                    reveal(job);
                }

                @Override
                public void changed() {
                    gridSignature = "";
                    refresh(true);
                }

                @Override
                public void retry(AudiobookJobs.Job job) {
                    Optional<LibraryGroups.Group> group =
                        job.groupKey == null
                            ? Optional.empty()
                            : LibraryGroups.find(library.tracks(), job.groupKey);
                    if (group.isEmpty()) {
                        Notification.show(
                            "The recordings for \u201c" +
                                job.title +
                                "\u201d are no longer in the library.",
                            4000,
                            Notification.Position.BOTTOM_START
                        );
                        return;
                    }
                    openAudiobookDialog(group.get());
                }

                @Override
                public boolean replaced(AudiobookJobs.Job job) {
                    if (job.resultTrackId == null) {
                        return false;
                    }
                    for (LibraryGroups.Group group : LibraryGroups.groups(library.tracks())) {
                        for (LibraryTrack track : group.tracks()) {
                            if (job.resultTrackId.equals(track.id)) {
                                return group.isEarlierVersion(track);
                            }
                        }
                    }
                    return false;
                }
            }
        );

        addClassName("library-view");
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        setAlignItems(Alignment.STRETCH);

        buildNowCard();
        buildToolbar();
        buildGrid();
        buildCards();
        buildEmptyState();

        nowCard.setWidthFull();
        player.setWidthFull();
        toolbar.setWidthFull();
        grid.setWidthFull();
        cardList.setWidthFull();
        add(nowCard, player, queuePanel, toolbar, grid, cardList, emptyState);

        refresh(true);
    }

    private void buildNowCard() {
        nowCard.addClassName("now-card");

        nowCover.addClassName("cover");
        nowCover.addClassName("now-cover");
        nowCoverPlaceholder.setText("♪");
        nowCoverPlaceholder.addClassName("cover-placeholder");
        nowCoverImage.setAlt("cover");
        nowCoverImage.setVisible(false);
        // Never show a broken image: if a cover cannot be loaded (the track just changed, the
        // connection blipped), try once more and show the placeholder until a cover loads.
        nowCoverImage.getElement().executeJs(
            """
            const img = this;
            const placeholder = img.previousElementSibling;
            img.addEventListener('load', () => {
              img.style.visibility = '';
              if (placeholder) placeholder.hidden = true;
            });
            img.addEventListener('error', () => {
              img.style.visibility = 'hidden';
              if (placeholder) placeholder.hidden = false;
              const src = img.getAttribute('src') || '';
              if (src && !src.includes('&retry=')) {
                setTimeout(() => {
                  if (img.getAttribute('src') === src) img.src = src + '&retry=1';
                }, 1500);
              }
            });
            """
        );
        nowCover.add(nowCoverPlaceholder, nowCoverImage);

        Div body = new Div();
        body.addClassName("now-body");
        nowEyebrow.addClassName("eyebrow");
        nowTitle.addClassName("now-title");
        nowSub.addClassName("now-sub");

        Div progress = new Div();
        progress.addClassName("progress");
        progressBar.addClassName("bar");
        progress.add(progressBar);

        HorizontalLayout meta = new HorizontalLayout();
        meta.addClassName("now-meta");
        meta.setWidthFull();
        meta.setJustifyContentMode(JustifyContentMode.BETWEEN);
        nowTime.addClassName("now-time");
        nowClient.addClassName("now-client");
        meta.add(nowTime, nowClient);

        body.add(nowEyebrow, nowTitle, nowSub, progress, meta);
        nowCard.add(nowCover, body);
    }

    private Div toolbar;

    private void buildToolbar() {
        toolbar = new Div();
        toolbar.addClassName("lib-toolbar");
        H2 heading = new H2("Library");
        heading.addClassName("lib-heading");
        trackCount.addClassName("count");
        trackCount.getElement().setAttribute("aria-live", "polite");
        groupCount.addClassName("count");
        groupCount.addClassName("count-secondary");
        heading.add(trackCount, groupCount);

        Div tools = new Div();
        tools.addClassName("lib-tools");
        search.setPlaceholder("Search title, artist, album…");
        search.getElement().setAttribute("aria-label", "Search recordings");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        search.setValueChangeTimeout(250);
        search.addValueChangeListener(event -> refreshGrid());
        search.setWidth("260px");
        totalSize.addClassName("total-size");
        totalSize.getElement().setAttribute("title", "Total library size on disk");
        tools.add(search, totalSize);

        toolbar.add(heading, tools);
    }

    private final H3 emptyTitle = new H3();
    private final Span emptyText = new Span();
    private Button emptyClear;

    private void buildEmptyState() {
        emptyState.addClassName("lib-empty");
        emptyState.getElement().setAttribute("role", "status");
        emptyState.getElement().setAttribute("aria-live", "polite");
        emptyTitle.addClassName("lib-empty-title");
        emptyText.addClassName("lib-empty-text");
        emptyClear = new Button("Clear search", event -> search.clear());
        emptyClear.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        emptyState.add(emptyTitle, emptyText, emptyClear);
        emptyState.setVisible(false);
    }

    private void buildCards() {
        cardList.addClassName("lib-cards");
    }

    private void buildGrid() {
        grid.addClassName("library-grid");
        grid.setSelectionMode(Grid.SelectionMode.NONE);
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.setPartNameGenerator(row -> row.groupRow() ? "album-row" : null);
        grid.addExpandListener(event ->
            event
                .getItems()
                .stream()
                .filter(Row::groupRow)
                .forEach(row -> expandedGroups.add(row.key()))
        );
        grid.addCollapseListener(event ->
            event
                .getItems()
                .stream()
                .filter(Row::groupRow)
                .forEach(row -> expandedGroups.remove(row.key()))
        );

        grid.addComponentColumn(this::coverCell)
            .setWidth("64px")
            .setFlexGrow(0)
            .setResizable(true)
            .setKey("cover");
        grid.addComponentHierarchyColumn(this::titleCell)
            .setHeader("Title")
            .setFlexGrow(3)
            .setResizable(true)
            .setKey("title");
        grid.addComponentColumn(row -> {
            Span duration = new Span(
                Fmt.duration(row.groupRow() ? row.group().durationMs() : row.track().durationMs)
            );
            duration.addClassName("cell-number");
            duration.getElement().setAttribute("title", "Listening time");
            return duration;
        })
            .setHeader("Duration")
            .setAutoWidth(true)
            .setFlexGrow(0)
            .setResizable(true)
            .setKey("duration");
        grid.addComponentColumn(this::formatCell)
            .setHeader("Download")
            .setAutoWidth(true)
            .setFlexGrow(0)
            .setResizable(true)
            .setKey("formats");
        grid.addComponentColumn(row -> {
            Span size = new Span(
                Fmt.bytes(row.groupRow() ? row.group().sizeBytes() : row.track().sizeBytes)
            );
            size.addClassName("cell-number");
            size.getElement().setAttribute(
                "title",
                row.groupRow()
                    ? "Total on disk, including source recordings and .m4b"
                    : "File size on disk"
            );
            return size;
        })
            .setHeader("Size")
            .setAutoWidth(true)
            .setFlexGrow(0)
            .setResizable(true)
            .setKey("size");
        grid.addComponentColumn(row -> {
            Span date = new Span(
                row.groupRow()
                    ? groupDateHint(row.group())
                    : Fmt.date(row.track().recordedAtEpochMs)
            );
            date.addClassName("cell-date");
            date.getElement().setAttribute(
                "title",
                row.groupRow() ? "First to last recording in this group" : "Recording date"
            );
            return date;
        })
            .setHeader("Recorded")
            .setWidth("160px")
            .setFlexGrow(0)
            .setResizable(true)
            .setKey("date");
        grid.addComponentColumn(this::actionsCell)
            .setWidth("170px")
            .setFlexGrow(0)
            .setKey("actions");
    }

    private Component coverCell(Row row) {
        LibraryTrack coverTrack = row.groupRow()
            ? row
                  .group()
                  .tracks()
                  .stream()
                  .filter(track -> track.cover)
                  .findFirst()
                  .orElse(null)
            : row.track();
        Div holder = new Div();
        holder.addClassName("grid-cover");
        if (coverTrack != null && coverTrack.cover) {
            Image image = new Image(AppUrls.relative("api/tracks/" + coverTrack.id + "/cover"), "");
            image.addClassName("grid-cover-image");
            holder.add(image);
        } else {
            Span note = new Span("♪");
            note.addClassName("cover-placeholder");
            holder.add(note);
        }
        return holder;
    }

    private Component titleCell(Row row) {
        return row.groupRow() ? groupTitleCell(row) : trackTitleCell(row);
    }

    private Component groupTitleCell(Row row) {
        LibraryGroups.Group group = row.group();
        Div body = new Div();
        body.addClassName("grid-title");
        body.getElement().setAttribute("data-group-key", group.key());
        H3 title = new H3(
            group.title() == null || group.title().isBlank() ? "Untitled" : group.title()
        );
        title.addClassName("grid-title-text");
        Span artist = new Span(group.artist() == null ? "" : group.artist());
        artist.addClassName("grid-artist");
        body.add(title, artist);

        Div badges = new Div();
        badges.addClassName("grid-badges");
        Optional<LibraryTrack> m4b = group.m4b();
        if (group.isAudiobook()) {
            badges.add(badge("audiobook", BadgeVariant.SUCCESS));
            if (m4b.isPresent() && m4b.get().chapterCount != null) {
                badges.add(badge(chapters(m4b.get().chapterCount), BadgeVariant.CONTRAST));
            } else {
                Badge noM4b = badge("no m4b", BadgeVariant.CONTRAST);
                noM4b
                    .getElement()
                    .setAttribute(
                        "title",
                        "No audiobook file yet. Create one from the group actions."
                    );
                badges.add(noM4b);
            }
        } else {
            badges.add(badge("album", BadgeVariant.CONTRAST));
        }
        if (m4b.isPresent()) {
            long books = group
                .tracks()
                .stream()
                .filter(track -> track.chapterCount != null)
                .count();
            int sources = (int) Math.max(0, group.tracks().size() - books);
            if (sources > 0) {
                badges.add(
                    badge(
                        sources + (sources == 1 ? " source file" : " source files"),
                        BadgeVariant.CONTRAST
                    )
                );
            }
            if (books > 1) {
                long earlier = books - 1;
                Badge versions = badge(
                    earlier + (earlier == 1 ? " earlier version" : " earlier versions"),
                    BadgeVariant.CONTRAST
                );
                versions
                    .getElement()
                    .setAttribute(
                        "title",
                        "M4B downloads the newest build. Earlier builds are listed inside the group."
                    );
                badges.add(versions);
            }
        } else {
            String unit = group.isAudiobook()
                ? group.tracks().size() == 1
                    ? " recording"
                    : " recordings"
                : group.tracks().size() == 1
                  ? " track"
                  : " tracks";
            badges.add(badge(group.tracks().size() + unit, BadgeVariant.CONTRAST));
        }
        int duplicates = group.duplicateCount();
        if (duplicates > 0) {
            Badge duplicateBadge = badge(
                duplicates + (duplicates == 1 ? " duplicate take" : " duplicate takes"),
                BadgeVariant.WARNING
            );
            duplicateBadge
                .getElement()
                .setAttribute(
                    "title",
                    "ZIP downloads and audiobook assembly use the best take only. " +
                        "Every take stays downloadable."
                );
            badges.add(duplicateBadge);
        }
        body.add(badges);
        return body;
    }

    private void openAudiobookDialog(LibraryGroups.Group group) {
        new AudiobookDialog(
            library,
            settingsService,
            audiobookCreator,
            audiobookJobs,
            group,
            job -> {
                gridSignature = "";
                refresh(true);
                queuePanel.scrollIntoView();
                showQueued(job);
            }
        ).open();
    }

    /**
     * Confirms the queue entry. The queue panel is already scrolled into view, so this only
     * acknowledges the click and never hides the progress behind a popup.
     */
    private void showQueued(AudiobookJobs.Job job) {
        Notification notification = new Notification();
        notification.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
        notification.setPosition(Notification.Position.BOTTOM_END);
        notification.setDuration(4000);
        Span text = new Span("\u201c" + job.title + "\u201d added to the queue");
        text.addClassName("queue-toast-text");
        notification.add(text);
        notification.open();
    }

    /** Scrolls to the group that holds a finished audiobook, expands it and flashes the row. */
    private void reveal(AudiobookJobs.Job job) {
        if (job == null || job.resultTrackId == null) {
            return;
        }
        // The book's group key changes when the sources are linked to the new .m4b, so look the
        // group up by the result instead of the key the job was queued under.
        Optional<LibraryGroups.Group> group = LibraryGroups.groups(library.tracks())
            .stream()
            .filter(candidate ->
                candidate
                    .tracks()
                    .stream()
                    .anyMatch(track -> job.resultTrackId.equals(track.id))
            )
            .findFirst();
        if (group.isEmpty()) {
            Notification.show(
                "\u201c" + job.title + "\u201d is no longer in the library.",
                4000,
                Notification.Position.BOTTOM_START
            );
            return;
        }
        String key = group.get().key();
        search.clear();
        expandedGroups.add(key);
        expandedCards.add(key);
        gridSignature = "";
        refresh(true);
        getElement().executeJs(
            """
            const key = $0;
            const scope = this;
            requestAnimationFrame(() => {
              const targets = [...scope.querySelectorAll('[data-group-key]')]
                  .filter((element) => element.dataset.groupKey === key && element.offsetParent);
              const target = targets[0];
              if (!target) return;
              const row = target.closest('.lib-card') || target;
              row.scrollIntoView({block: 'center', behavior: 'smooth'});
              row.classList.remove('reveal-flash');
              void row.offsetWidth;
              row.classList.add('reveal-flash');
              setTimeout(() => row.classList.remove('reveal-flash'), 2400);
            });
            """,
            key
        );
    }

    private Button audiobookIcon(LibraryGroups.Group group) {
        Button button = new Button(VaadinIcon.HEADPHONES.create(), event ->
            openAudiobookDialog(group)
        );
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        button.addClassName("row-action");
        boolean running = audiobookJobs.isActive(group.key());
        button.setEnabled(!running);
        String label = running
            ? "Conversion in progress — see the queue above"
            : "Create audiobook (.m4b) from these recordings";
        button.getElement().setAttribute("title", label);
        button.getElement().setAttribute("aria-label", label);
        return button;
    }

    private Component trackTitleCell(Row row) {
        LibraryTrack track = row.track();
        Div body = new Div();
        body.addClassName("grid-title");
        H3 title = new H3(
            track.title == null || track.title.isBlank() ? track.displayName() : track.title
        );
        title.addClassName("grid-title-text");
        Span artist = new Span(track.artist == null ? "" : track.artist);
        artist.addClassName("grid-artist");
        Span album = new Span(track.album == null ? "" : track.album);
        album.addClassName("grid-album");
        body.add(title, artist, album);

        Div badges = new Div();
        badges.addClassName("grid-badges");
        if (track.audiobookChapter) {
            badges.add(badge("chapter", BadgeVariant.SUCCESS));
        }
        if (track.chapterCount != null) {
            if (row.group() != null && row.group().isEarlierVersion(track)) {
                Badge earlier = badge("earlier version", BadgeVariant.CONTRAST);
                earlier
                    .getElement()
                    .setAttribute(
                        "title",
                        "Replaced by a newer build of this audiobook. The group download is the " +
                            "newest file; delete this one when you no longer need it."
                    );
                badges.add(earlier);
            } else {
                badges.add(badge("audiobook", BadgeVariant.SUCCESS));
            }
            badges.add(badge(chapters(track.chapterCount), BadgeVariant.CONTRAST));
        }
        LibraryGroups.Take take = takeInfo.get(track.id);
        if (take != null) {
            if (take.best()) {
                Badge best = badge("best take", BadgeVariant.SUCCESS);
                best.getElement().setAttribute("title", takeHint(take));
                badges.add(best);
            } else {
                Badge badge = badge("take " + take.index(), BadgeVariant.CONTRAST);
                badge.getElement().setAttribute("title", takeHint(take));
                badges.add(badge);
            }
        }
        if (track.metadataSource != null && !track.metadataSource.isBlank()) {
            badges.add(badge("via " + track.metadataSource, BadgeVariant.CONTRAST));
        }
        if (track.incomplete) {
            Badge incomplete = badge("incomplete", BadgeVariant.WARNING);
            incomplete
                .getElement()
                .setAttribute(
                    "title",
                    "Playback stopped after " +
                        Fmt.duration(track.durationMs) +
                        " of " +
                        Fmt.duration(track.expectedDurationMs) +
                        ". The file exists but is shortened."
                );
            badges.add(incomplete);
        }
        if (track.status != null && !track.status.equals("ready")) {
            Badge status = badge(
                track.status,
                "error".equals(track.status) ? BadgeVariant.ERROR : BadgeVariant.WARNING
            );
            if (track.error != null) {
                status.getElement().setAttribute("title", track.error);
            }
            badges.add(status);
        }
        if ("error".equals(track.status) && track.error != null && !track.error.isBlank()) {
            Span error = new Span(track.error);
            error.addClassName("grid-error");
            error.getElement().setAttribute("title", track.error);
            body.add(error);
        }
        if (badges.getComponentCount() > 0) {
            body.add(badges);
        }
        return body;
    }

    private static String takeHint(LibraryGroups.Take take) {
        return (
            "Take " +
            take.index() +
            " of " +
            take.total() +
            ". ZIP downloads and audiobook assembly use the best take: " +
            "complete before incomplete, then fewer lost packets, then longer."
        );
    }

    private Component formatCell(Row row) {
        Div chips = new Div();
        chips.addClassName("format-chips");
        if (row.groupRow()) {
            LibraryGroups.Group group = row.group();
            Set<String> formats = group.formats(true);
            for (String format : FORMAT_ORDER) {
                if (!formats.contains(format)) {
                    continue;
                }
                if ("m4b".equals(format)) {
                    group.m4b().ifPresent(m4b -> chips.add(downloadChip(m4b, format)));
                } else {
                    chips.add(groupZipChip(group, format));
                }
            }
        } else {
            addTrackChips(chips, row.track());
        }
        return chips;
    }

    private Anchor groupZipChip(LibraryGroups.Group group, String format) {
        long size = 0;
        int count = 0;
        for (LibraryTrack track : group.bestTakes()) {
            if (track.files.containsKey(format)) {
                size += library.fileSize(track, format);
                count++;
            }
        }
        Anchor chip = new Anchor(
            AppUrls.relative("api/groups/" + group.key() + "/zip/" + format),
            "ZIP · " + formatLabel(format) + " ↓"
        );
        chip.addClassName("format-chip");
        chip.addClassName("zip-chip");
        String hint =
            "Download " +
            count +
            (count == 1 ? " best take" : " best takes") +
            " as ZIP · " +
            formatName(format) +
            (size > 0 ? " · " + Fmt.bytes(size) : "");
        chip.getElement().setAttribute("title", hint);
        chip.getElement().setAttribute("aria-label", hint);
        return chip;
    }

    private void addTrackChips(Div chips, LibraryTrack track) {
        for (String format : FORMAT_ORDER) {
            if (!track.files.containsKey(format)) {
                continue;
            }
            if ("ready".equals(track.status)) {
                chips.add(downloadChip(track, format));
            } else {
                Badge unavailable = badge(formatLabel(format), BadgeVariant.CONTRAST);
                unavailable
                    .getElement()
                    .setAttribute(
                        "title",
                        "error".equals(track.status)
                            ? "Export failed — this file is not downloadable"
                            : "Still processing — not downloadable yet"
                    );
                chips.add(unavailable);
            }
        }
        if (track.gaps > 0) {
            long lostMs = track.gapPackets * 8L;
            String label = track.gaps == 1 ? "1 audio gap" : track.gaps + " audio gaps";
            if (lostMs >= 1_000) {
                label += " · " + String.format(Locale.ROOT, "%.1f s", lostMs / 1000.0);
            }
            Badge chip = badge(label, lostMs >= 2_000 ? BadgeVariant.ERROR : BadgeVariant.WARNING);
            StringBuilder hint = new StringBuilder(
                track.gaps == 1
                    ? "One run of audio packets could not be recovered."
                    : track.gaps + " runs of audio packets could not be recovered."
            );
            if (track.gapPackets > 0) {
                hint.append(' ').append(track.gapPackets).append(" packets in total");
                hint.append(" (~").append(lostMs).append(" ms)");
                hint.append(
                    track.filledPackets >= track.gapPackets
                        ? ", replaced with silence so the timing stays intact."
                        : "."
                );
            }
            if (track.recoveredGaps > 0) {
                hint.append(' ').append(recoveredHint(track));
            }
            hint.append(
                " Most gaps are tiny and inaudible. These are not chapters; " +
                    "re-record only if you hear dropouts. Lost-packet recovery in Settings " +
                    "controls how long the receiver waits for retransmits."
            );
            chip.getElement().setAttribute("title", hint.toString());
            chips.add(chip);
        }
    }

    private static String recoveredHint(LibraryTrack track) {
        return (
            (track.recoveredGaps == 1
                ? "1 other gap was"
                : track.recoveredGaps + " other gaps were") +
            " recovered from retransmits (longest wait " +
            track.maxRecoveryMs +
            " ms)."
        );
    }

    /** One format chip doubles as the download link for exactly that file. */
    private Anchor downloadChip(LibraryTrack track, String format) {
        Anchor chip = new Anchor(fileUrl(track, format, true), formatLabel(format) + " ↓");
        chip.addClassName("format-chip");
        chip.getElement().setAttribute("download", track.files.get(format));
        chip.getElement().setAttribute("title", downloadHint(track, format));
        chip.getElement().setAttribute("aria-label", downloadHint(track, format));
        return chip;
    }

    private static String fileUrl(LibraryTrack track, String format, boolean download) {
        return AppUrls.relative(
            "api/tracks/" + track.id + "/file/" + format + (download ? "?download=1" : "")
        );
    }

    private String downloadHint(LibraryTrack track, String format) {
        long size = library.fileSize(track, format);
        return "Download " + formatName(format) + (size > 0 ? " · " + Fmt.bytes(size) : "");
    }

    private static String formatLabel(String format) {
        return switch (format) {
            case "m4b" -> "M4B";
            case "m4a" -> "M4A";
            case "mp3" -> "MP3";
            case "alac" -> "ALAC";
            case "caf" -> "CAF";
            default -> format.toUpperCase(Locale.ROOT);
        };
    }

    private String formatName(String format) {
        return switch (format) {
            case "m4b" -> "audiobook (M4B)";
            case "m4a" -> "AAC (M4A)";
            case "mp3" -> "MP3";
            case "alac" -> "lossless ALAC (M4A)";
            case "caf" -> "lossless capture (CAF)";
            default -> format.toUpperCase(Locale.ROOT);
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String chapters(int count) {
        return count + (count == 1 ? " chapter" : " chapters");
    }

    private static Badge badge(String text, BadgeVariant... variants) {
        Badge badge = new Badge(text);
        badge.addThemeVariants(variants);
        return badge;
    }

    private Component actionsCell(Row row) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.addClassName("grid-actions");
        actions.setSpacing(false);
        if (row.groupRow()) {
            LibraryGroups.Group group = row.group();
            Optional<AudiobookJobs.Job> job = audiobookJobs.latest(group.key());
            boolean active = job.filter(AudiobookJobs.Job::active).isPresent();
            if (active) {
                AudiobookJobs.Job current = job.get();
                Badge converting = badge(
                    current.status() == AudiobookJobs.State.QUEUED
                        ? "queued"
                        : Math.round(current.progress * 100) + "%",
                    BadgeVariant.WARNING
                );
                converting
                    .getElement()
                    .setAttribute("title", current.step == null ? "Converting" : current.step);
                actions.add(converting);
            } else {
                job.filter(finished -> finished.status() == AudiobookJobs.State.FAILED).ifPresent(
                    failed -> {
                        Badge failedBadge = badge("failed", BadgeVariant.ERROR);
                        failedBadge
                            .getElement()
                            .setAttribute(
                                "title",
                                failed.error == null ? "Conversion failed" : failed.error
                            );
                        actions.add(failedBadge);
                    }
                );
                if (group.isAudiobook()) {
                    Button audiobook = new Button(
                        group.hasM4b() ? "Rebuild M4B…" : "Create M4B…",
                        event -> openAudiobookDialog(group)
                    );
                    audiobook.addThemeVariants(
                        ButtonVariant.LUMO_SMALL,
                        ButtonVariant.LUMO_TERTIARY
                    );
                    audiobook.addClassName("book-action");
                    audiobook
                        .getElement()
                        .setAttribute(
                            "title",
                            "Create a chaptered audiobook from these recordings"
                        );
                    actions.add(audiobook);
                } else if (group.tracks().size() > 1) {
                    actions.add(audiobookIcon(group));
                }
            }
            actions.add(groupMenu(group));
            return actions;
        }
        LibraryTrack track = row.track();

        String format = preferredFormat(track);
        if (format != null && "ready".equals(track.status)) {
            Button play = player.playButton(
                fileUrl(track, format, false),
                track.displayName(),
                String.join(" · ", track.files.keySet())
            );

            Anchor download = new Anchor();
            download.setHref(fileUrl(track, format, true));
            download.add(VaadinIcon.DOWNLOAD.create());
            download.addClassName("row-action");
            download.getElement().setAttribute("download", track.files.get(format));
            download.getElement().setAttribute("title", downloadHint(track, format));
            download.getElement().setAttribute("aria-label", downloadHint(track, format));

            actions.add(play, download);
        }

        Button delete = new Button(VaadinIcon.TRASH.create(), event -> confirmDelete(track));
        delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        delete.addClassName("row-action");
        delete.addClassName("danger");
        delete.getElement().setAttribute("title", "Delete");
        delete.getElement().setAttribute("aria-label", "Delete " + track.displayName());

        boolean audiobookAction =
            (!row.group().isAudiobook() && track.durationMs >= 15 * 60 * 1000L) ||
            (row.group().isAudiobook() && row.group().tracks().size() == 1);
        if (audiobookAction) {
            actions.add(trackMenu(track, row.group()));
        } else {
            actions.add(delete);
        }
        return actions;
    }

    private Button trackMenu(LibraryTrack track, LibraryGroups.Group group) {
        Button trigger = new Button(VaadinIcon.ELLIPSIS_DOTS_H.create());
        trigger.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        trigger.addClassName("row-action");
        trigger.getElement().setAttribute("title", "Recording actions");
        trigger
            .getElement()
            .setAttribute("aria-label", "Recording actions for " + track.displayName());

        ContextMenu menu = new ContextMenu(trigger);
        menu.setOpenOnClick(true);
        boolean running = audiobookJobs.isActive(group.key());
        var audiobook = menu.addItem(
            group.hasM4b() ? "Rebuild audiobook…" : "Create audiobook…",
            event -> openAudiobookDialog(group)
        );
        audiobook.setEnabled(!running);
        menu.addItem("Delete recording…", event -> confirmDelete(track));
        return trigger;
    }

    private Button groupMenu(LibraryGroups.Group group) {
        Button trigger = new Button(VaadinIcon.ELLIPSIS_DOTS_H.create());
        trigger.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        trigger.addClassName("row-action");
        trigger.getElement().setAttribute("title", "Group actions");
        trigger
            .getElement()
            .setAttribute(
                "aria-label",
                "Group actions for " + (group.title() == null ? "this group" : group.title())
            );

        ContextMenu menu = new ContextMenu(trigger);
        menu.setOpenOnClick(true);
        var create = menu.addItem(
            group.hasM4b() ? "Rebuild audiobook…" : "Create audiobook…",
            event -> openAudiobookDialog(group)
        );
        create.setEnabled(!audiobookJobs.isActive(group.key()));
        if (group.hasM4b()) {
            menu.addItem("Delete audiobook file…", event -> confirmDeleteM4b(group));
        }
        menu.addItem("Delete all recordings…", event -> confirmDeleteGroup(group));
        return trigger;
    }

    private void confirmDelete(LibraryTrack track) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete recording?");
        dialog.setText("This removes \"" + track.displayName() + "\" from disk.");
        dialog.setCancelable(true);
        dialog.setCancelText("Cancel");
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            library.delete(track);
            Log.info("library", "deleted " + track.displayName());
            gridSignature = "";
            refresh(true);
            Notification.show("Deleted " + track.displayName());
        });
        dialog.open();
    }

    private void confirmDeleteM4b(LibraryGroups.Group group) {
        group.m4b().ifPresent(m4b -> {
            ConfirmDialog dialog = new ConfirmDialog();
            dialog.setHeader("Delete audiobook file?");
            dialog.setText("This removes the .m4b only. Source recordings stay in the library.");
            dialog.setCancelable(true);
            dialog.setCancelText("Cancel");
            dialog.setConfirmText("Delete .m4b");
            dialog.setConfirmButtonTheme("error primary");
            dialog.addConfirmListener(event -> {
                library.delete(m4b);
                gridSignature = "";
                refresh(true);
                Notification.show("Deleted audiobook file");
            });
            dialog.open();
        });
    }

    private void confirmDeleteGroup(LibraryGroups.Group group) {
        int count = group.tracks().size();
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete recordings?");
        dialog.setText(
            "This removes \"" +
                group.title() +
                "\" with " +
                count +
                (count == 1 ? " recording" : " recordings") +
                " from disk."
        );
        dialog.setCancelable(true);
        dialog.setCancelText("Cancel");
        dialog.setConfirmText("Delete all");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            for (LibraryTrack track : group.tracks()) {
                library.delete(track);
            }
            gridSignature = "";
            refresh(true);
            Notification.show("Deleted " + group.title());
        });
        dialog.open();
    }

    private String preferredFormat(LibraryTrack track) {
        for (String format : FORMAT_ORDER) {
            if (track.files.containsKey(format)) {
                return format;
            }
        }
        return null;
    }

    private void refresh(boolean force) {
        refreshNow();
        queuePanel.refresh();
        refreshGrid(force);
    }

    private void refreshNow() {
        boolean live = state.receiving;
        nowCard.removeClassNames("live", "idle");
        nowCard.addClassName(live ? "live" : "idle");
        boolean hasTrackInfo =
            !isBlank(state.nowTitle) || !isBlank(state.nowArtist) || state.coverVersion > 0;
        nowEyebrow.setText(
            live
                ? hasTrackInfo
                    ? "Recording now"
                    : "Recording now · waiting for track info"
                : "Waiting for a sender"
        );
        nowTitle.setText(
            live
                ? state.nowTitle == null || state.nowTitle.isBlank()
                    ? "Unknown title"
                    : state.nowTitle
                : "Select this receiver in the AirPlay menu"
        );
        if (live) {
            String artist = state.nowArtist == null ? "" : state.nowArtist;
            String album = state.nowAlbum == null ? "" : state.nowAlbum;
            nowSub.setText(
                (artist + (album.isBlank() ? "" : (artist.isBlank() ? "" : " · ") + album)).trim()
            );
        } else {
            nowSub.setText("Playback from iPhone, iPad or Mac is recorded track by track.");
        }
        nowTime.setText(
            live
                ? Fmt.duration(state.nowRecordedMs) +
                      " / " +
                      (state.nowDurationMs > 0 ? Fmt.duration(state.nowDurationMs) : "–")
                : ""
        );
        if (live) {
            String source = isBlank(state.clientName) ? state.client : state.clientName;
            StringBuilder label = new StringBuilder("from " + (source == null ? "" : source));
            if (!isBlank(state.senderApp)) {
                label.append(" · ").append(state.senderApp);
            }
            if (!isBlank(state.clientName) && !isBlank(state.client)) {
                label.append(" · ").append(state.client);
            }
            nowClient.setText(label.toString());
        } else {
            nowClient.setText("");
        }

        double percent =
            live && state.nowDurationMs > 0
                ? Math.min(100.0, (state.nowRecordedMs * 100.0) / state.nowDurationMs)
                : 0;
        progressBar.getStyle().set("width", String.format(Locale.ROOT, "%.1f%%", percent));

        boolean hasCover = live && state.coverVersion > 0;
        nowCoverImage.setVisible(hasCover);
        nowCoverPlaceholder.setVisible(!hasCover);
        if (hasCover && shownCoverVersion != state.coverVersion) {
            nowCoverImage.setSrc(AppUrls.relative("api/now/cover?v=" + state.coverVersion));
            shownCoverVersion = state.coverVersion;
        } else if (!hasCover) {
            shownCoverVersion = -1;
        }
    }

    private void refreshGrid() {
        refreshGrid(false);
    }

    private void refreshGrid(boolean force) {
        List<LibraryTrack> tracks = library.tracks();
        String rawQuery = search.getValue() == null ? "" : search.getValue().trim();
        String query = rawQuery.toLowerCase(Locale.ROOT);

        takeInfo.clear();
        List<Row> roots = new ArrayList<>();
        Map<Row, List<Row>> children = new HashMap<>();
        for (LibraryGroups.Group group : LibraryGroups.groups(tracks)) {
            for (List<LibraryTrack> takeList : LibraryGroups.takes(group.tracks()).values()) {
                for (int i = 0; i < takeList.size(); i++) {
                    takeInfo.put(
                        takeList.get(i).id,
                        new LibraryGroups.Take(i + 1, takeList.size(), i == 0)
                    );
                }
            }

            boolean groupMatches = query.isBlank() || matchesGroup(group, query);
            List<LibraryTrack> matchedTracks = query.isBlank()
                ? group.tracks()
                : group
                      .tracks()
                      .stream()
                      .filter(track -> matches(track, query))
                      .toList();
            if (!groupMatches && matchedTracks.isEmpty()) {
                continue;
            }
            if (!group.expandable()) {
                LibraryTrack only = group.tracks().get(0);
                roots.add(new Row("t" + only.id, group, only));
                continue;
            }
            Row root = new Row("g" + group.key(), group, null);
            roots.add(root);
            List<LibraryTrack> visibleTracks = groupMatches ? group.tracks() : matchedTracks;
            List<Row> rows = new ArrayList<>();
            for (LibraryTrack track : visibleTracks) {
                if (
                    group
                        .m4b()
                        .map(current -> current == track)
                        .orElse(false)
                ) {
                    continue; // the group header already offers the current .m4b
                }
                rows.add(new Row("t" + track.id, group, track));
            }
            children.put(root, rows);
        }

        int visibleTracks = 0;
        for (Row root : roots) {
            if (root.groupRow()) {
                visibleTracks += children.getOrDefault(root, List.of()).size();
            } else {
                visibleTracks++;
            }
        }
        trackCount.setText(String.valueOf(visibleTracks));
        groupCount.setText(roots.size() + (roots.size() == 1 ? " group" : " groups"));
        groupCount.setVisible(!roots.isEmpty());
        long totalBytes = tracks
            .stream()
            .mapToLong(track -> track.sizeBytes)
            .sum();
        totalSize.setText(Fmt.bytes(totalBytes));

        boolean empty = roots.isEmpty();
        grid.setVisible(!empty);
        cardList.getElement().getClassList().set("is-empty", empty);
        emptyState.setVisible(empty);
        if (empty) {
            if (rawQuery.isBlank()) {
                emptyTitle.setText("No recordings yet");
                emptyText.setText(
                    "Play something on your iPhone, iPad or Mac and pick \"" +
                        (isBlank(state.deviceName) ? "the recorder" : state.deviceName) +
                        "\" in the AirPlay menu."
                );
            } else {
                emptyTitle.setText("No matches");
                emptyText.setText("No recording matches \"" + rawQuery + "\".");
            }
            emptyClear.setVisible(!rawQuery.isBlank());
        }

        StringBuilder signature = new StringBuilder();
        for (Row root : roots) {
            appendSignature(signature, root);
            for (Row child : children.getOrDefault(root, List.of())) {
                signature.append('>');
                appendSignature(signature, child);
            }
            signature.append(';');
        }
        if (force || !signature.toString().equals(gridSignature)) {
            gridSignature = signature.toString();
            TreeData<Row> data = new TreeData<>();
            data.addRootItems(roots);
            List<Row> expand = new ArrayList<>();
            for (Map.Entry<Row, List<Row>> entry : children.entrySet()) {
                data.addItems(entry.getKey(), entry.getValue());
                if (!query.isBlank() || expandedGroups.contains(entry.getKey().key())) {
                    expand.add(entry.getKey());
                }
            }
            grid.setDataProvider(new TreeDataProvider<>(data, HierarchyFormat.NESTED));
            if (!expand.isEmpty()) {
                grid.expand(expand);
            }
            renderCards(roots, children, !query.isBlank());
        }
    }

    private void renderCards(List<Row> roots, Map<Row, List<Row>> children, boolean autoExpand) {
        cardList.removeAll();
        for (Row root : roots) {
            if (!root.groupRow()) {
                cardList.add(trackCard(root));
                continue;
            }
            Div card = new Div();
            card.addClassName("lib-card");
            card.addClassName("lib-card-group");
            Div head = new Div();
            head.addClassName("card-head");
            head.add(coverCell(root), titleCell(root));
            card.add(head);

            Div meta = new Div();
            meta.addClassName("card-meta");
            meta.add(
                new Span(
                    metaLine(
                        root.group().durationMs(),
                        root.group().sizeBytes(),
                        groupDateHint(root.group())
                    )
                )
            );
            card.add(meta);

            Div files = new Div();
            files.addClassName("card-files");
            files.add(formatCell(root));

            Div actions = new Div();
            actions.addClassName("card-actions");
            actions.add(actionsCell(root));

            Div controls = new Div();
            controls.addClassName("card-controls");
            controls.add(files, actions);
            card.add(controls);

            List<Row> rows = children.getOrDefault(root, List.of());
            if (!rows.isEmpty()) {
                Div childList = new Div();
                childList.addClassName("card-children");
                for (Row child : rows) {
                    childList.add(trackCard(child));
                }
                boolean expanded = autoExpand || expandedCards.contains(root.group().key());
                childList.setVisible(expanded);
                Button toggle = new Button(
                    expanded ? "Hide recordings" : "Show " + rows.size() + " recordings"
                );
                toggle.setIcon(
                    expanded ? VaadinIcon.CHEVRON_UP.create() : VaadinIcon.CHEVRON_DOWN.create()
                );
                toggle.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                toggle.addClassName("card-toggle");
                toggle.getElement().setAttribute("aria-expanded", String.valueOf(expanded));
                toggle.addClickListener(event -> {
                    boolean open = !childList.isVisible();
                    childList.setVisible(open);
                    if (open) {
                        expandedCards.add(root.group().key());
                    } else {
                        expandedCards.remove(root.group().key());
                    }
                    toggle.setText(
                        open ? "Hide recordings" : "Show " + rows.size() + " recordings"
                    );
                    toggle.setIcon(
                        open ? VaadinIcon.CHEVRON_UP.create() : VaadinIcon.CHEVRON_DOWN.create()
                    );
                    toggle.getElement().setAttribute("aria-expanded", String.valueOf(open));
                });
                card.add(toggle, childList);
            }
            cardList.add(card);
        }
    }

    private Component trackCard(Row row) {
        Div card = new Div();
        card.addClassName("lib-card");
        card.addClassName("lib-card-track");
        Div head = new Div();
        head.addClassName("card-head");
        head.add(coverCell(row), titleCell(row));
        card.add(head);

        LibraryTrack track = row.track();
        Div meta = new Div();
        meta.addClassName("card-meta");
        meta.add(
            new Span(metaLine(track.durationMs, track.sizeBytes, Fmt.date(track.recordedAtEpochMs)))
        );
        card.add(meta);

        Div files = new Div();
        files.addClassName("card-files");
        files.add(formatCell(row));

        Div actions = new Div();
        actions.addClassName("card-actions");
        actions.add(actionsCell(row));

        Div controls = new Div();
        controls.addClassName("card-controls");
        controls.add(files, actions);
        card.add(controls);
        return card;
    }

    private static String metaLine(long durationMs, long sizeBytes, String date) {
        List<String> parts = new ArrayList<>();
        if (durationMs > 0) {
            parts.add(Fmt.duration(durationMs));
        }
        String size = Fmt.bytes(sizeBytes);
        if (!size.isBlank()) {
            parts.add(size);
        }
        if (date != null && !date.isBlank()) {
            parts.add(date);
        }
        return String.join(" · ", parts);
    }

    private static String groupDateHint(LibraryGroups.Group group) {
        long first = group
            .tracks()
            .stream()
            .mapToLong(track -> track.recordedAtEpochMs)
            .filter(value -> value > 0)
            .min()
            .orElse(0);
        long last = group
            .tracks()
            .stream()
            .mapToLong(track -> track.recordedAtEpochMs)
            .max()
            .orElse(0);
        return Fmt.dateRange(first, last);
    }

    private static boolean matchesGroup(LibraryGroups.Group group, String query) {
        String haystack =
            (group.title() == null ? "" : group.title()) +
            " " +
            (group.artist() == null ? "" : group.artist());
        return (
            matchesText(haystack, query) ||
            group
                .tracks()
                .stream()
                .anyMatch(track -> matches(track, query))
        );
    }

    private void appendSignature(StringBuilder signature, Row row) {
        if (row.groupRow()) {
            signature
                .append('g')
                .append(row.group().key())
                .append('|')
                .append(row.group().title())
                .append('|')
                .append(row.group().tracks().size())
                .append('|')
                .append(row.group().duplicateCount())
                .append('|')
                .append(jobSignature(row.group().key()))
                .append('|')
                .append(
                    row
                        .group()
                        .m4b()
                        .map(track -> track.sizeBytes + "|" + track.files)
                        .orElse("no-m4b")
                )
                .append('|')
                .append(row.group().sizeBytes());
            return;
        }
        LibraryTrack track = row.track();
        signature
            .append(track.id)
            .append('|')
            .append(track.status)
            .append('|')
            .append(track.sizeBytes)
            .append('|')
            .append(track.durationMs)
            .append('|')
            .append(track.gaps)
            .append('|')
            .append(track.gapPackets)
            .append('|')
            .append(track.recoveredGaps)
            .append('|')
            .append(track.incomplete)
            .append('|')
            .append(track.chapterCount)
            .append('|')
            .append(track.bookId)
            .append('|')
            .append(track.files);
        if (row.group() != null) {
            signature.append('|').append(jobSignature(row.group().key()));
        }
    }

    /** Queue state of a group, so the grid redraws when a conversion starts, advances or ends. */
    private String jobSignature(String groupKey) {
        return audiobookJobs
            .latest(groupKey)
            .map(job -> job.state + "|" + Math.round(job.progress * 100) + "|" + job.error)
            .orElse("none");
    }

    private static boolean matches(LibraryTrack track, String query) {
        if (query.isBlank()) {
            return true;
        }
        String haystack = String.join(
            " ",
            track.title == null ? "" : track.title,
            track.artist == null ? "" : track.artist,
            track.album == null ? "" : track.album,
            track.baseName == null ? "" : track.baseName
        );
        return matchesText(haystack, query);
    }

    private static boolean matchesText(String haystack, String query) {
        String value = haystack.toLowerCase(Locale.ROOT);
        for (String part : query.split("\\s+")) {
            if (!value.contains(part)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        registration = broadcaster.register(() -> {
            UI ui = getUI().orElse(null);
            if (ui != null) {
                ui.access(() -> refresh(false));
            }
        });
        UI ui = attachEvent.getUI();
        resizeRegistration = ui
            .getPage()
            .addBrowserWindowResizeListener(event -> applyResponsiveLayout(event.getWidth()));
        ui.getPage()
            .getExtendedClientDetails()
            .refresh(details -> applyResponsiveLayout(details.getWindowInnerWidth()));
        refresh(true);
    }

    private void applyResponsiveLayout(int width) {
        if (width <= 0) {
            return;
        }
        boolean compact = width < 1300;
        setColumnVisible("size", !compact);
        setColumnVisible("date", !compact);
    }

    private void setColumnVisible(String key, boolean visible) {
        var column = grid.getColumnByKey(key);
        if (column != null) {
            column.setVisible(visible);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        if (resizeRegistration != null) {
            resizeRegistration.remove();
            resizeRegistration = null;
        }
        super.onDetach(detachEvent);
    }
}
