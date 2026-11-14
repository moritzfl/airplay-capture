package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.config.SettingsService;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.util.NetworkTools;
import de.moritzf.airplaycapture.util.UpdateChecker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Receiver and export configuration: one card per topic, a section list to jump between them
 * and a save bar that shows whether there are unsaved changes.
 */
@Route(value = "settings", layout = MainLayout.class)
public class SettingsView extends VerticalLayout implements BeforeLeaveObserver {

    /** DOM id of the Updates card, so the header's update notice can jump to it. */
    static final String UPDATES_ID = "settings-updates";

    private final SettingsService settingsService;
    private final Library library;
    private final UiState state;
    private final UpdateChecker updateChecker;
    private final UiBroadcaster broadcaster;
    private Registration registration;

    private final TextField deviceName = new TextField("Receiver name");
    private final ComboBox<String> interfaceAddress = new ComboBox<>("Network interface");
    private final IntegerField port = new IntegerField("RTSP port");

    private final CheckboxGroup<String> formats = new CheckboxGroup<>("File formats");
    private final ComboBox<Integer> aacBitrate = new ComboBox<>("AAC bitrate");
    private final ComboBox<String> mp3Mode = new ComboBox<>("MP3 rate control");
    private final ComboBox<Integer> mp3Quality = new ComboBox<>("MP3 quality (VBR)");
    private final ComboBox<Integer> mp3Bitrate = new ComboBox<>("MP3 bitrate (CBR)");
    private final TextField ffmpegPath = new TextField("ffmpeg");
    private final Checkbox keepRaw = new Checkbox("Keep the lossless capture (.caf)");

    private final IntegerField minTrackSeconds = new IntegerField(
        "Discard recordings shorter than"
    );
    private final IntegerField idleSeconds = new IntegerField("End a track after silence of");
    private final ComboBox<Integer> packetRecovery = new ComboBox<>("Lost-packet recovery");

    private final Checkbox metadataEnrichment = new Checkbox(
        "Fill missing tags from Apple's catalog"
    );
    private final Checkbox metadataEnrichmentDeezer = new Checkbox(
        "Use Deezer when Apple has no match"
    );
    private final TextField audiobookLookupCountry = new TextField("Catalog country");

    private final Checkbox audiobookEnabled = new Checkbox("Detect audiobooks");
    private final TextField audiobookGenres = new TextField("Genre keywords");
    private final IntegerField audiobookLongTrackSeconds = new IntegerField(
        "Treat long recordings as audiobooks from"
    );
    private final Checkbox audiobookLookup = new Checkbox("Confirm with Apple Books");
    private final Checkbox audiobookLookupMusicBrainz = new Checkbox("Also search MusicBrainz");
    private final TextField audiobookLookupContact = new TextField("MusicBrainz contact");
    private final ComboBox<Integer> audiobookBitrateKbps = new ComboBox<>("Audiobook bitrate");
    private final Checkbox audiobookCatalogChapters = new Checkbox(
        "Suggest chapters from the catalog"
    );
    private final Checkbox audiobookSplitLongTracks = new Checkbox(
        "Split a single long recording at pauses"
    );
    private final Checkbox audiobookAutoAssemble = new Checkbox(
        "Create the audiobook automatically when a book ends"
    );
    private final IntegerField audiobookIdleSeconds = new IntegerField(
        "A book ends after silence of"
    );
    private final Checkbox audiobookKeepChapters = new Checkbox(
        "Keep the chapter recordings afterwards"
    );

    private final Checkbox updateCheck = new Checkbox("Check for new versions");
    private final Span updateVersion = new Span();
    private final Span updateText = new Span();
    private final Anchor updateLink = new Anchor();
    private final Button checkNow = new Button("Check now", event -> checkForUpdate());

    private final TextField libraryPath = new TextField("Recordings folder");
    private final Span saveState = new Span();
    private final Button save = new Button("Save settings", event -> save());
    private final Button discard = new Button("Discard", event -> discardChanges());
    private final Div saveBar = new Div();

    private final List<String> localAddresses = new ArrayList<>();
    private boolean loading;
    private boolean dirty;

    public SettingsView(
        SettingsService settingsService,
        Library library,
        UiState state,
        UpdateChecker updateChecker,
        UiBroadcaster broadcaster
    ) {
        this.settingsService = settingsService;
        this.library = library;
        this.state = state;
        this.updateChecker = updateChecker;
        this.broadcaster = broadcaster;

        addClassName("settings-view");
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        setAlignItems(Alignment.STRETCH);

        H2 heading = new H2("Settings");
        heading.addClassName("view-heading");

        configureFields();

        Map<String, Div> sections = new LinkedHashMap<>();
        sections.put(
            "Receiver",
            section(
                "How this recorder appears in the AirPlay menu of your devices.",
                form(deviceName, interfaceAddress, port)
            )
        );
        sections.put(
            "Files",
            section(
                "Every finished recording is exported with ffmpeg in the formats chosen here.",
                form(
                    wide(formats),
                    mp3Mode,
                    mp3Quality,
                    mp3Bitrate,
                    aacBitrate,
                    ffmpegPath,
                    wide(libraryPath),
                    wide(keepRaw)
                )
            )
        );
        sections.put(
            "Recording",
            section(
                "When a track starts and ends, and how hard the receiver tries to repair dropouts.",
                form(minTrackSeconds, idleSeconds, wide(packetRecovery))
            )
        );
        sections.put(
            "Metadata",
            section(
                "Senders often leave out album, genre, year, track number or cover. Lookups send " +
                    "title and artist to the named service; turn them off to keep that on this machine.",
                form(metadataEnrichment, metadataEnrichmentDeezer, audiobookLookupCountry)
            )
        );
        sections.put(
            "Audiobooks",
            section(
                "Chapters that belong together are grouped as one book that can be turned into a " +
                    "single chaptered .m4b.",
                form(
                    wide(audiobookEnabled),
                    subheading("Detection"),
                    audiobookGenres,
                    audiobookLongTrackSeconds,
                    audiobookLookup,
                    audiobookLookupMusicBrainz,
                    audiobookLookupContact,
                    subheading("Chapters and file"),
                    audiobookCatalogChapters,
                    audiobookSplitLongTracks,
                    audiobookBitrateKbps,
                    subheading("Automatic mode"),
                    wide(audiobookAutoAssemble),
                    audiobookIdleSeconds,
                    audiobookKeepChapters
                )
            )
        );

        Div updates = section(
            "New versions are published on GitHub as a Synology package, a Docker image and a jar.",
            updatePanel()
        );
        updates.setId(UPDATES_ID);
        sections.put("Updates", updates);

        Div nav = new Div();
        nav.addClassName("settings-nav");
        nav.getElement().setAttribute("role", "navigation");
        nav.getElement().setAttribute("aria-label", "Settings sections");
        Div cards = new Div();
        cards.addClassName("settings-sections");
        sections.forEach((title, card) -> {
            H3 sectionHeading = new H3(title);
            sectionHeading.addClassName("section-title");
            card.addComponentAsFirst(sectionHeading);
            cards.add(card);
            Button jump = new Button(title, event ->
                card
                    .getElement()
                    .executeJs("this.scrollIntoView({behavior: 'smooth', block: 'start'})")
            );
            jump.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            jump.addClassName("settings-nav-link");
            nav.add(jump);
        });

        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        // Restarting the receiver takes a few seconds; a second click must not queue another.
        save.setDisableOnClick(true);
        save.addClassName("save-button");
        discard.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        saveState.addClassName("save-state");
        saveState.getElement().setAttribute("role", "status");
        Div saveActions = new Div(discard, save);
        saveActions.addClassName("save-actions");
        saveBar.addClassName("save-bar");
        saveBar.add(saveState, saveActions);
        cards.add(saveBar);

        Div layout = new Div(nav, cards);
        layout.addClassName("settings-layout");
        add(heading, layout);

        load(settingsService.current());
        allFields().forEach(field -> field.addValueChangeListener(event -> changed()));
    }

    private Component updatePanel() {
        updateVersion.addClassName("update-version");
        updateText.addClassName("update-text");
        updateText.getElement().setAttribute("role", "status");
        updateLink.setText("Release notes and download");
        updateLink.addClassName("update-link");
        updateLink.getElement().setAttribute("target", "_blank");
        updateLink.getElement().setAttribute("rel", "noopener noreferrer");
        checkNow.addThemeVariants(ButtonVariant.LUMO_SMALL);
        checkNow.addClassName("update-check");
        updateCheck.setHelperText(
            "Once a day, asks api.github.com for the latest release. Nothing about your " +
                "recordings is sent."
        );

        Div summary = new Div(updateVersion, updateText, updateLink);
        summary.addClassName("update-summary");
        Div state = new Div(summary, checkNow);
        state.addClassName("update-state");
        Div panel = new Div(state, updateCheck);
        panel.addClassName("update-panel");
        return panel;
    }

    /** What the last check found; refreshed when a check finishes in the background. */
    private void showUpdateStatus() {
        UpdateChecker.Status status = updateChecker.status();
        boolean known = !status.currentVersion().isBlank();
        updateVersion.setText(
            known ? "AirPlay Capture " + status.currentVersion() : "Development build"
        );
        boolean available = status.updateAvailable() && status.latest() != null;
        updateLink.setVisible(available);
        getElement().getClassList().set("update-available", available);
        String checked =
            status.checkedAtEpochMs() > 0
                ? " Checked " + Fmt.dateTime(status.checkedAtEpochMs()) + "."
                : "";
        if (available) {
            UpdateChecker.Release latest = status.latest();
            String published = publishedDate(latest.publishedAt());
            updateText.setText(
                "Version " +
                    latest.version() +
                    " is available" +
                    (published.isBlank() ? "." : ", published " + published + ".")
            );
            updateLink.setHref(latest.url());
        } else if (!known) {
            updateText.setText(
                "This build has no version, so it cannot be compared with releases."
            );
        } else if (!status.error().isBlank()) {
            updateText.setText("Could not ask GitHub: " + status.error() + "." + checked);
        } else if (status.checkedAtEpochMs() > 0) {
            updateText.setText("This is the latest version." + checked);
        } else {
            updateText.setText(
                updateChecker.enabled() ? "Not checked yet." : "The daily check is turned off."
            );
        }
        checkNow.setVisible(known);
    }

    private static String publishedDate(String iso) {
        try {
            return Fmt.date(Instant.parse(iso).toEpochMilli());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void checkForUpdate() {
        UI ui = UI.getCurrent();
        checkNow.setEnabled(false);
        checkNow.setText("Checking…");
        Thread.ofVirtual()
            .name("update-check-now")
            .start(() -> {
                updateChecker.checkNow();
                try {
                    ui.access(() -> {
                        checkNow.setEnabled(true);
                        checkNow.setText("Check now");
                        showUpdateStatus();
                    });
                } catch (RuntimeException ignored) {
                    // the page was closed while checking
                }
            });
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        registration = broadcaster.register(() -> {
            UI ui = getUI().orElse(null);
            if (ui != null) {
                ui.access(this::showUpdateStatus);
            }
        });
        showUpdateStatus();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        super.onDetach(detachEvent);
    }

    private static Div section(String description, Component content) {
        Div card = new Div();
        card.addClassNames("panel-card", "settings-section");
        Span text = new Span(description);
        text.addClassName("section-description");
        card.add(text, content);
        return card;
    }

    private static FormLayout form(Component... fields) {
        FormLayout layout = new FormLayout();
        layout.addClassName("settings-form");
        layout.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1),
            new FormLayout.ResponsiveStep("560px", 2)
        );
        for (Component field : fields) {
            layout.add(field);
            if (field.getElement().hasAttribute("data-wide")) {
                layout.setColspan(field, 2);
            }
        }
        return layout;
    }

    /** Marks a field that spans both form columns. */
    private static <T extends Component> T wide(T field) {
        field.getElement().setAttribute("data-wide", true);
        return field;
    }

    private static H4 subheading(String text) {
        H4 heading = new H4(text);
        heading.addClassName("form-subheading");
        return wide(heading);
    }

    private static Span unit(String text) {
        Span unit = new Span(text);
        unit.addClassName("field-unit");
        return unit;
    }

    private void configureFields() {
        deviceName.setRequiredIndicatorVisible(true);
        deviceName.setMaxLength(60);
        deviceName.setHelperText("Shown in the AirPlay menu of your iPhone, iPad or Mac.");

        localAddresses.addAll(NetworkTools.localAddresses().keySet());
        Map<String, String> interfaces = NetworkTools.localAddresses();
        interfaceAddress.setItems(localAddresses);
        interfaceAddress.setItemLabelGenerator(address ->
            interfaces.containsKey(address) ? address + " · " + interfaces.get(address) : address
        );
        interfaceAddress.setAllowCustomValue(true);
        interfaceAddress.addCustomValueSetListener(event -> {
            String value = event.getDetail() == null ? "" : event.getDetail().trim();
            if (value.isEmpty()) {
                interfaceAddress.clear();
                return;
            }
            if (!localAddresses.contains(value)) {
                localAddresses.add(value);
                interfaceAddress.setItems(localAddresses);
            }
            interfaceAddress.setValue(value);
        });
        interfaceAddress.setClearButtonVisible(true);
        interfaceAddress.setPlaceholder("Automatic");
        interfaceAddress.setHelperText(
            "Leave on Automatic unless the receiver is announced on the wrong network, " +
                "for example a VPN or Docker interface."
        );

        port.setMin(0);
        port.setMax(65535);
        port.setStepButtonsVisible(true);
        port.setHelperText(
            "0 picks a free port between 5000 and 6000. Set a fixed port for firewall rules."
        );

        formats.setItems("m4a", "mp3", "alac");
        formats.setItemLabelGenerator(
            item ->
                switch (item) {
                    case "m4a" -> "AAC (.m4a) · plays everywhere";
                    case "mp3" -> "MP3 · for older players";
                    case "alac" -> "ALAC (.m4a) · lossless, large";
                    default -> item;
                }
        );
        formats.addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL);
        formats.setRequiredIndicatorVisible(true);

        aacBitrate.setItems(List.of(64, 96, 128, 160, 192, 224, 256, 320));
        aacBitrate.setItemLabelGenerator(SettingsView::aacLabel);

        mp3Mode.setItems("vbr", "cbr");
        mp3Mode.setItemLabelGenerator(mode ->
            "vbr".equals(mode) ? "Variable bitrate (VBR)" : "Constant bitrate (CBR)"
        );
        mp3Mode.setHelperText(
            "VBR keeps the quality constant; CBR gives a predictable size for old players."
        );

        mp3Quality.setItems(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        mp3Quality.setItemLabelGenerator(SettingsView::mp3Label);

        mp3Bitrate.setItems(List.of(96, 128, 160, 192, 224, 256, 320));
        mp3Bitrate.setItemLabelGenerator(SettingsView::mp3BitrateLabel);

        ffmpegPath.setPlaceholder("ffmpeg");
        libraryPath.setReadOnly(true);
        libraryPath.setHelperText(
            "Set by the installation: AIRPLAY_CAPTURE_LIBRARY_DIR, or Manage in the Synology package."
        );
        keepRaw.setHelperText(
            "The original AirPlay audio before conversion, roughly 5 to 7 MB per minute."
        );

        minTrackSeconds.setMin(0);
        minTrackSeconds.setMax(600);
        minTrackSeconds.setStepButtonsVisible(true);
        minTrackSeconds.setSuffixComponent(unit("s"));
        minTrackSeconds.setHelperText("Drops accidental taps and skipped songs.");

        idleSeconds.setMin(1);
        idleSeconds.setMax(120);
        idleSeconds.setStepButtonsVisible(true);
        idleSeconds.setSuffixComponent(unit("s"));
        idleSeconds.setHelperText("Used when the sender does not announce the next track.");

        packetRecovery.setItems(List.of(500, 1000, 1500, 2500, 4000, 6000, 10000));
        packetRecovery.setItemLabelGenerator(SettingsView::recoveryLabel);
        packetRecovery.setHelperText(
            "How long the receiver keeps asking the sender for missing audio. A longer wait " +
                "repairs more dropouts, a shorter one finishes tracks sooner."
        );

        metadataEnrichment.setHelperText(
            "Only empty fields are filled; what the sender sent wins."
        );
        metadataEnrichmentDeezer.setHelperText("A second catalog, asked only after Apple.");
        audiobookLookupCountry.setMaxLength(2);
        audiobookLookupCountry.setPattern("[A-Za-z]{2}");
        audiobookLookupCountry.setHelperText(
            "Two-letter country of the Apple catalog to search, for example DE or US."
        );

        audiobookEnabled.setHelperText(
            "Groups chapters as a book and offers Create M4B… in the library."
        );
        audiobookGenres.setHelperText(
            "A recording with one of these genres starts a book. Separate with commas."
        );
        audiobookLongTrackSeconds.setMin(0);
        audiobookLongTrackSeconds.setMax(7200);
        audiobookLongTrackSeconds.setStep(60);
        audiobookLongTrackSeconds.setStepButtonsVisible(true);
        audiobookLongTrackSeconds.setSuffixComponent(unit("s"));
        audiobookLongTrackSeconds.setHelperText("900 s is 15 minutes. 0 turns this rule off.");
        audiobookLookup.setHelperText(
            "Recognizes books that have no audiobook genre. Sends album and artist to itunes.apple.com."
        );
        audiobookLookupMusicBrainz.setHelperText(
            "At most one request per second, cached per album."
        );
        audiobookLookupContact.setPlaceholder("Optional");
        audiobookLookupContact.setHelperText(
            "MusicBrainz asks for a contact with each request, for example an email address."
        );

        audiobookCatalogChapters.setHelperText(
            "Chapter names and lengths from Deezer and MusicBrainz, aligned to pauses in the " +
                "recording. Without a matching edition the pauses are used."
        );
        audiobookSplitLongTracks.setHelperText(
            "For books recorded as one file: a chapter at pauses over 2.5 s, at least 5 minutes apart."
        );
        audiobookBitrateKbps.setItems(List.of(32, 48, 64, 80, 96, 128, 160, 192));
        audiobookBitrateKbps.setItemLabelGenerator(SettingsView::audiobookBitrateLabel);

        audiobookAutoAssemble.setHelperText(
            "Off: you choose recordings, title and chapters in Create M4B… first."
        );
        audiobookIdleSeconds.setMin(30);
        audiobookIdleSeconds.setMax(7200);
        audiobookIdleSeconds.setStep(30);
        audiobookIdleSeconds.setStepButtonsVisible(true);
        audiobookIdleSeconds.setSuffixComponent(unit("s"));
        audiobookIdleSeconds.setHelperText("How long to wait for the next chapter.");
        audiobookKeepChapters.setHelperText("Off deletes the chapter files once the .m4b exists.");

        for (TextField field : List.of(
            deviceName,
            ffmpegPath,
            audiobookLookupCountry,
            audiobookGenres,
            audiobookLookupContact
        )) {
            field.setValueChangeMode(ValueChangeMode.LAZY);
            field.setValueChangeTimeout(250);
        }
        for (IntegerField field : numberFields()) {
            field.setValueChangeMode(ValueChangeMode.LAZY);
            field.setValueChangeTimeout(250);
        }
    }

    private List<IntegerField> numberFields() {
        return List.of(
            port,
            minTrackSeconds,
            idleSeconds,
            audiobookLongTrackSeconds,
            audiobookIdleSeconds
        );
    }

    private List<HasValue<?, ?>> allFields() {
        return List.of(
            deviceName,
            interfaceAddress,
            port,
            formats,
            aacBitrate,
            mp3Mode,
            mp3Quality,
            mp3Bitrate,
            ffmpegPath,
            keepRaw,
            minTrackSeconds,
            idleSeconds,
            packetRecovery,
            metadataEnrichment,
            metadataEnrichmentDeezer,
            audiobookLookupCountry,
            audiobookEnabled,
            audiobookGenres,
            audiobookLongTrackSeconds,
            audiobookLookup,
            audiobookLookupMusicBrainz,
            audiobookLookupContact,
            audiobookBitrateKbps,
            audiobookCatalogChapters,
            audiobookSplitLongTracks,
            audiobookAutoAssemble,
            audiobookIdleSeconds,
            audiobookKeepChapters,
            updateCheck
        );
    }

    private static String aacLabel(int kbps) {
        return switch (kbps) {
            case 64 -> "64 kbps · speech";
            case 96 -> "96 kbps · podcast";
            case 128 -> "128 kbps · standard";
            case 192 -> "192 kbps · recommended for music";
            case 256 -> "256 kbps · very high";
            case 320 -> "320 kbps · maximum";
            default -> kbps + " kbps";
        };
    }

    private static String audiobookBitrateLabel(int kbps) {
        return switch (kbps) {
            case 32, 48 -> kbps + " kbps · voice";
            case 64 -> "64 kbps · compact";
            case 96 -> "96 kbps · recommended for spoken word";
            case 128 -> "128 kbps · higher";
            case 192 -> "192 kbps · maximum";
            default -> kbps + " kbps";
        };
    }

    private static String mp3Label(int quality) {
        return switch (quality) {
            case 0 -> "V0 · ~245 kbps (best)";
            case 1 -> "V1 · ~225 kbps";
            case 2 -> "V2 · ~190 kbps (recommended)";
            case 3 -> "V3 · ~175 kbps";
            case 4 -> "V4 · ~165 kbps";
            case 5 -> "V5 · ~130 kbps";
            case 6 -> "V6 · ~115 kbps";
            case 7 -> "V7 · ~100 kbps";
            case 8 -> "V8 · ~85 kbps";
            default -> "V9 · ~65 kbps (smallest)";
        };
    }

    private static String recoveryLabel(int millis) {
        return switch (millis) {
            case 500 -> "0.5 s · fastest";
            case 1000 -> "1 s · quick";
            case 1500 -> "1.5 s";
            case 2500 -> "2.5 s · recommended";
            case 4000 -> "4 s · patient";
            case 6000 -> "6 s · very patient";
            default -> "10 s · maximum";
        };
    }

    private static String mp3BitrateLabel(int kbps) {
        return switch (kbps) {
            case 96 -> "96 kbps · small";
            case 128 -> "128 kbps · standard";
            case 192 -> "192 kbps · recommended";
            case 256 -> "256 kbps · high";
            case 320 -> "320 kbps · maximum";
            default -> kbps + " kbps";
        };
    }

    private static void selectPreset(ComboBox<Integer> box, int value) {
        List<Integer> items = new ArrayList<>(box.getListDataView().getItems().toList());
        if (!items.contains(value)) {
            items.add(value);
            items.sort(Integer::compareTo);
            box.setItems(items);
        }
        box.setValue(value);
    }

    private void load(Settings settings) {
        loading = true;
        try {
            deviceName.setValue(settings.deviceName);
            port.setValue(settings.port);
            String address = settings.interfaceAddress == null ? "" : settings.interfaceAddress;
            if (!address.isBlank() && !localAddresses.contains(address)) {
                localAddresses.add(address);
                interfaceAddress.setItems(localAddresses);
            }
            interfaceAddress.setValue(address.isBlank() ? null : address);
            formats.setValue(new LinkedHashSet<>(settings.formats));
            selectPreset(aacBitrate, settings.aacBitrateKbps);
            mp3Mode.setValue(settings.mp3Mode);
            selectPreset(mp3Quality, settings.mp3Quality);
            selectPreset(mp3Bitrate, settings.mp3BitrateKbps);
            selectPreset(packetRecovery, settings.packetRecoveryMs);
            minTrackSeconds.setValue(settings.minTrackSeconds);
            idleSeconds.setValue(settings.idleFinalizeSeconds);
            keepRaw.setValue(settings.keepRaw);
            metadataEnrichment.setValue(settings.metadataEnrichmentEnabled);
            metadataEnrichmentDeezer.setValue(settings.metadataEnrichmentDeezer);
            ffmpegPath.setValue(settings.ffmpegPath == null ? "ffmpeg" : settings.ffmpegPath);
            audiobookEnabled.setValue(settings.audiobookEnabled);
            audiobookAutoAssemble.setValue(settings.audiobookAutoAssemble);
            audiobookGenres.setValue(settings.audiobookGenres);
            audiobookLongTrackSeconds.setValue(settings.audiobookLongTrackSeconds);
            audiobookIdleSeconds.setValue(settings.audiobookIdleSeconds);
            selectPreset(audiobookBitrateKbps, settings.audiobookBitrateKbps);
            audiobookKeepChapters.setValue(settings.audiobookKeepChapters);
            audiobookSplitLongTracks.setValue(settings.audiobookSplitLongTracks);
            audiobookCatalogChapters.setValue(settings.audiobookCatalogChapters);
            audiobookLookup.setValue(settings.audiobookLookupEnabled);
            audiobookLookupCountry.setValue(settings.audiobookLookupCountry);
            audiobookLookupMusicBrainz.setValue(settings.audiobookLookupMusicBrainz);
            audiobookLookupContact.setValue(
                settings.audiobookLookupContact == null ? "" : settings.audiobookLookupContact
            );
            updateCheck.setValue(settings.updateCheckEnabled);
            libraryPath.setValue(library.libraryDir().toString());
            clearErrors();
        } finally {
            loading = false;
        }
        showFfmpegState();
        showUpdateStatus();
        changed();
    }

    private void discardChanges() {
        load(settingsService.current());
    }

    /** Whether the saved ffmpeg command works; the answer to "why is there no M4A?". */
    private void showFfmpegState() {
        ffmpegPath.setHelperText(
            state.ffmpegAvailable
                ? "Found. Command name or full path of the ffmpeg program."
                : "Not found: recordings stay as lossless .caf until this points to ffmpeg."
        );
        ffmpegPath.getElement().getClassList().set("field-warning", !state.ffmpegAvailable);
    }

    /** Runs after every edit: dependent fields, validation of touched values and the save bar. */
    private void changed() {
        if (loading) {
            return;
        }
        updateDependencies();
        Settings edited = collect();
        Settings saved = settingsService.current().copy();
        dirty = !edited.sameAs(saved) || hasEmptyNumber();
        boolean restart =
            !edited.deviceName.equals(saved.deviceName) ||
            edited.port != saved.port ||
            !edited.interfaceAddress.equals(saved.interfaceAddress);
        showBar(
            dirty ? "dirty" : null,
            !restart
                ? "Unsaved changes"
                : state.receiving
                  ? "Unsaved changes · saving restarts the receiver and ends the current recording"
                  : "Unsaved changes · saving restarts the receiver"
        );
    }

    /**
     * The save bar only takes space when it has something to say: unsaved edits, the result of
     * the last save, or a problem. {@code kind} is its style class, {@code null} hides it.
     */
    private void showBar(String kind, String text) {
        saveBar.removeClassNames("dirty", "saved", "error");
        saveBar.setVisible(kind != null);
        if (kind == null) {
            return;
        }
        saveBar.addClassName(kind);
        saveState.setText(text);
        boolean editable = !"saved".equals(kind);
        save.setVisible(editable);
        discard.setVisible(editable);
        save.setEnabled(dirty);
        discard.setEnabled(dirty);
    }

    private boolean hasEmptyNumber() {
        return numberFields().stream().anyMatch(IntegerField::isEmpty);
    }

    /** Options that have no effect right now are disabled, so it is clear what they belong to. */
    private void updateDependencies() {
        Set<String> selected = formats.getValue() == null ? Set.of() : formats.getValue();
        boolean mp3 = selected.contains("mp3");
        boolean vbr = !"cbr".equals(mp3Mode.getValue());
        aacBitrate.setEnabled(selected.contains("m4a"));
        mp3Mode.setEnabled(mp3);
        mp3Quality.setEnabled(mp3);
        mp3Bitrate.setEnabled(mp3);
        mp3Quality.setVisible(vbr);
        mp3Bitrate.setVisible(!vbr);
        metadataEnrichmentDeezer.setEnabled(metadataEnrichment.getValue());

        boolean books = audiobookEnabled.getValue();
        boolean automatic = books && audiobookAutoAssemble.getValue();
        for (HasEnabled field : List.<HasEnabled>of(
            audiobookGenres,
            audiobookLongTrackSeconds,
            audiobookLookup,
            audiobookLookupMusicBrainz,
            audiobookBitrateKbps,
            audiobookCatalogChapters,
            audiobookSplitLongTracks,
            audiobookAutoAssemble
        )) {
            field.setEnabled(books);
        }
        audiobookLookupContact.setEnabled(books && audiobookLookupMusicBrainz.getValue());
        audiobookIdleSeconds.setEnabled(automatic);
        audiobookKeepChapters.setEnabled(automatic);
    }

    /** The settings as edited; empty numbers keep the saved value until they are corrected. */
    private Settings collect() {
        Settings current = settingsService.current();
        Settings next = current.copy();
        next.deviceName = deviceName.getValue() == null ? "" : deviceName.getValue().trim();
        next.port = number(port, current.port);
        next.interfaceAddress =
            interfaceAddress.getValue() == null ? "" : interfaceAddress.getValue().trim();
        Set<String> selected = formats.getValue() == null ? Set.of() : formats.getValue();
        next.formats = new ArrayList<>(
            List.of("m4a", "mp3", "alac").stream().filter(selected::contains).toList()
        );
        next.aacBitrateKbps = choice(aacBitrate, current.aacBitrateKbps);
        next.mp3Quality = choice(mp3Quality, current.mp3Quality);
        next.mp3Mode = mp3Mode.getValue() == null ? current.mp3Mode : mp3Mode.getValue();
        next.mp3BitrateKbps = choice(mp3Bitrate, current.mp3BitrateKbps);
        next.packetRecoveryMs = choice(packetRecovery, current.packetRecoveryMs);
        next.minTrackSeconds = number(minTrackSeconds, current.minTrackSeconds);
        next.idleFinalizeSeconds = number(idleSeconds, current.idleFinalizeSeconds);
        next.keepRaw = keepRaw.getValue();
        next.metadataEnrichmentEnabled = metadataEnrichment.getValue();
        next.metadataEnrichmentDeezer = metadataEnrichmentDeezer.getValue();
        next.ffmpegPath = text(ffmpegPath).isBlank() ? "ffmpeg" : text(ffmpegPath);
        next.audiobookEnabled = audiobookEnabled.getValue();
        next.audiobookAutoAssemble = audiobookAutoAssemble.getValue();
        next.audiobookGenres = text(audiobookGenres);
        next.audiobookLongTrackSeconds = number(
            audiobookLongTrackSeconds,
            current.audiobookLongTrackSeconds
        );
        next.audiobookIdleSeconds = number(audiobookIdleSeconds, current.audiobookIdleSeconds);
        next.audiobookBitrateKbps = choice(audiobookBitrateKbps, current.audiobookBitrateKbps);
        next.audiobookKeepChapters = audiobookKeepChapters.getValue();
        next.audiobookSplitLongTracks = audiobookSplitLongTracks.getValue();
        next.audiobookCatalogChapters = audiobookCatalogChapters.getValue();
        next.audiobookLookupEnabled = audiobookLookup.getValue();
        next.audiobookLookupCountry = text(audiobookLookupCountry).toUpperCase(Locale.ROOT);
        next.audiobookLookupMusicBrainz = audiobookLookupMusicBrainz.getValue();
        next.audiobookLookupContact = text(audiobookLookupContact);
        next.updateCheckEnabled = updateCheck.getValue();
        return next;
    }

    private static String text(TextField field) {
        return field.getValue() == null ? "" : field.getValue().trim();
    }

    private static int number(IntegerField field, int fallback) {
        return field.getValue() == null ? fallback : field.getValue();
    }

    private static int choice(ComboBox<Integer> box, int fallback) {
        return box.getValue() == null ? fallback : box.getValue();
    }

    private void clearErrors() {
        deviceName.setInvalid(false);
        interfaceAddress.setInvalid(false);
        formats.setInvalid(false);
        audiobookLookupCountry.setInvalid(false);
        numberFields().forEach(field -> field.setInvalid(false));
    }

    /** Marks every problem on its field and returns the first one, or {@code null} when valid. */
    private Component firstInvalid() {
        clearErrors();
        List<Component> invalid = new ArrayList<>();
        if (text(deviceName).isBlank()) {
            deviceName.setErrorMessage("Enter a name for the AirPlay menu.");
            deviceName.setInvalid(true);
            invalid.add(deviceName);
        }
        String address = interfaceAddress.getValue();
        if (address != null && !NetworkTools.localAddresses().containsKey(address.trim())) {
            interfaceAddress.setErrorMessage(
                "No network interface of this machine has this address. Choose one from the list."
            );
            interfaceAddress.setInvalid(true);
            invalid.add(interfaceAddress);
        }
        for (IntegerField field : numberFields()) {
            Integer value = field.getValue();
            if (value == null || value < field.getMin() || value > field.getMax()) {
                field.setErrorMessage(
                    "Enter a number from " + field.getMin() + " to " + field.getMax() + "."
                );
                field.setInvalid(true);
                invalid.add(field);
            }
        }
        if (formats.getValue() == null || formats.getValue().isEmpty()) {
            formats.setErrorMessage("Choose at least one format, otherwise nothing is exported.");
            formats.setInvalid(true);
            invalid.add(formats);
        }
        if (!text(audiobookLookupCountry).matches("[A-Za-z]{2}")) {
            audiobookLookupCountry.setErrorMessage("Enter a two-letter country code such as DE.");
            audiobookLookupCountry.setInvalid(true);
            invalid.add(audiobookLookupCountry);
        }
        return invalid.isEmpty() ? null : invalid.get(0);
    }

    private void save() {
        Component invalid = firstInvalid();
        if (invalid != null) {
            invalid
                .getElement()
                .executeJs(
                    "this.scrollIntoView({behavior: 'smooth', block: 'center'}); this.focus && this.focus()"
                );
            showBar("error", "Not saved · check the highlighted fields");
            return;
        }
        try {
            load(settingsService.apply(collect()));
            showBar("saved", "Settings saved");
        } catch (Exception e) {
            // The values are stored; only applying them to the running receiver failed.
            load(settingsService.current());
            showBar("error", "Saved, but the receiver could not restart: " + e.getMessage());
        }
    }

    /** Leaving with unsaved edits asks first instead of silently dropping them. */
    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        if (!dirty) {
            return;
        }
        BeforeLeaveEvent.ContinueNavigationAction action = event.postpone();
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Discard unsaved changes?");
        dialog.setText("The settings you changed have not been saved.");
        dialog.setCancelable(true);
        dialog.setCancelText("Keep editing");
        dialog.setConfirmText("Discard changes");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(confirm -> action.proceed());
        dialog.addCancelListener(cancel -> action.cancel());
        dialog.open();
    }
}
