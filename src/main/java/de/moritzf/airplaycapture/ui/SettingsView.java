package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.UiState;
import de.moritzf.airplaycapture.config.SettingsService;
import de.moritzf.airplaycapture.library.Library;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Receiver and export configuration. */
@Route(value = "settings", layout = MainLayout.class)
public class SettingsView extends VerticalLayout {

    private final SettingsService settingsService;
    private final Library library;
    private final UiState state;

    private final TextField deviceName = new TextField("Receiver name");
    private final IntegerField port = new IntegerField("RTSP port");
    private final TextField interfaceAddress = new TextField("Interface address");
    private final CheckboxGroup<String> formats = new CheckboxGroup<>("Output formats");
    private final ComboBox<Integer> aacBitrate = new ComboBox<>("AAC bitrate");
    private final ComboBox<String> mp3Mode = new ComboBox<>("MP3 rate control");
    private final ComboBox<Integer> mp3Quality = new ComboBox<>("MP3 quality (VBR)");
    private final ComboBox<Integer> mp3Bitrate = new ComboBox<>("MP3 bitrate (CBR)");
    private final ComboBox<Integer> packetRecovery = new ComboBox<>("Lost-packet recovery");
    private final IntegerField minTrackSeconds = new IntegerField("Discard tracks shorter than (s)");
    private final IntegerField idleSeconds = new IntegerField("Finalize after silence (s)");
    private final Checkbox keepRaw = new Checkbox("Keep intermediate .caf files");
    private final Checkbox metadataEnrichment = new Checkbox("Fill missing tags from Apple catalog");
    private final Checkbox metadataEnrichmentDeezer = new Checkbox("Also use Deezer when Apple misses");
    private final TextField ffmpegPath = new TextField("ffmpeg binary");
    private final Checkbox audiobookEnabled = new Checkbox("Detect audiobooks and offer conversion");
    private final Checkbox audiobookAutoAssemble =
            new Checkbox("Assemble detected audiobooks automatically");
    private final TextField audiobookGenres = new TextField("Audiobook genre keywords");
    private final IntegerField audiobookLongTrackSeconds = new IntegerField("Long-track detection (s)");
    private final IntegerField audiobookIdleSeconds = new IntegerField("Assemble book after silence (s)");
    private final Checkbox audiobookLookup = new Checkbox("Confirm via Apple Books catalog");
    private final Checkbox audiobookLookupMusicBrainz = new Checkbox("Also check MusicBrainz");
    private final TextField audiobookLookupCountry = new TextField("Catalog country (ISO)");
    private final TextField audiobookLookupContact = new TextField("MusicBrainz contact (optional)");
    private final ComboBox<Integer> audiobookBitrateKbps = new ComboBox<>("Audiobook bitrate");
    private final Checkbox audiobookKeepChapters = new Checkbox("Keep chapter files after assembling");
    private final Checkbox audiobookSplitLongTracks =
            new Checkbox("Split single-file audiobooks at long pauses");
    private final Checkbox audiobookCatalogChapters =
            new Checkbox("Use catalog chapters in Create audiobook");

    private final Span libraryPath = new Span();
    private final Span ffmpegState = new Span();

    public SettingsView(SettingsService settingsService, Library library, UiState state) {
        this.settingsService = settingsService;
        this.library = library;
        this.state = state;

        addClassName("settings-view");
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        setAlignItems(Alignment.STRETCH);

        H2 heading = new H2("Settings");
        heading.addClassName("view-heading");

        Div card = new Div();
        card.addClassName("panel-card");

        configureFields();

        FormLayout general = formLayout();
        H3 receiverHeading = sectionHeading("Receiver");
        receiverHeading.addClassName("first-section");
        general.add(receiverHeading, deviceName, port, interfaceAddress);
        general.setColspan(receiverHeading, 2);

        H3 outputHeading = sectionHeading("Output");
        general.add(outputHeading, formats, aacBitrate, mp3Mode, mp3Quality, mp3Bitrate);
        general.setColspan(outputHeading, 2);

        H3 recordingHeading = sectionHeading("Recording");
        general.add(recordingHeading, minTrackSeconds, idleSeconds, packetRecovery,
                ffmpegPath, keepRaw);
        general.setColspan(recordingHeading, 2);

        H3 metadataHeading = sectionHeading("Metadata");
        general.add(metadataHeading, metadataEnrichment, metadataEnrichmentDeezer);
        general.setColspan(metadataHeading, 2);

        H3 audiobookHeading = sectionHeading("Audiobooks");
        FormLayout audiobooks = formLayout();
        audiobooks.add(audiobookHeading, audiobookEnabled, audiobookAutoAssemble,
                audiobookGenres, audiobookLookup, audiobookLookupMusicBrainz,
                audiobookLookupCountry, audiobookLookupContact, audiobookLongTrackSeconds,
                audiobookIdleSeconds, audiobookBitrateKbps, audiobookKeepChapters,
                audiobookSplitLongTracks, audiobookCatalogChapters);
        audiobooks.setColspan(audiobookHeading, 2);

        Button save = new Button("Save settings", event -> save());
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClassName("save-button");

        Div footer = new Div();
        footer.addClassName("settings-footer");
        libraryPath.addClassName("settings-note");
        ffmpegState.addClassName("settings-note");
        footer.add(save, libraryPath, ffmpegState);

        card.add(general, audiobooks, footer);
        add(heading, card);
        load(settingsService.current());
    }

    private static H3 sectionHeading(String text) {
        H3 heading = new H3(text);
        heading.addClassName("form-section");
        return heading;
    }

    private static FormLayout formLayout() {
        FormLayout layout = new FormLayout();
        layout.addClassName("settings-form");
        layout.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("720px", 2));
        return layout;
    }

    private void configureFields() {
        deviceName.setRequired(true);
        deviceName.setMaxLength(60);
        deviceName.setHelperText("Name shown in the AirPlay menu.");

        port.setMin(0);
        port.setMax(65535);
        port.setStepButtonsVisible(true);
        port.setHelperText("0 = first free port between 5000 and 6000.");

        interfaceAddress.setPlaceholder("auto");
        interfaceAddress.setHelperText("Optional IPv4 address to advertise on.");

        formats.setItems("m4a", "mp3", "alac");
        formats.setItemLabelGenerator(item -> switch (item) {
            case "m4a" -> "AAC (.m4a)";
            case "mp3" -> "MP3";
            case "alac" -> "ALAC (lossless)";
            default -> item;
        });
        formats.addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL);
        formats.setHelperText("ffmpeg converts each song after recording.");

        aacBitrate.setItems(List.of(64, 96, 128, 160, 192, 224, 256, 320));
        aacBitrate.setItemLabelGenerator(SettingsView::aacLabel);
        aacBitrate.setHelperText("AAC average bitrate. 192 kbps is the usual music setting.");

        mp3Mode.setItems("vbr", "cbr");
        mp3Mode.setItemLabelGenerator(mode -> "vbr".equals(mode)
                ? "Variable bitrate (VBR)"
                : "Constant bitrate (CBR)");
        mp3Mode.setHelperText("VBR keeps quality constant and lets the bitrate vary; "
                + "CBR gives a predictable size for old players.");
        mp3Mode.addValueChangeListener(event -> updateMp3Mode());

        mp3Quality.setItems(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        mp3Quality.setItemLabelGenerator(SettingsView::mp3Label);
        mp3Quality.setHelperText("LAME variable bitrate; lower V numbers keep more detail.");

        mp3Bitrate.setItems(List.of(96, 128, 160, 192, 224, 256, 320));
        mp3Bitrate.setItemLabelGenerator(SettingsView::mp3BitrateLabel);

        packetRecovery.setItems(List.of(500, 1000, 1500, 2500, 4000, 6000, 10000));
        packetRecovery.setItemLabelGenerator(SettingsView::recoveryLabel);
        packetRecovery.setHelperText("How long the receiver keeps asking the sender to retransmit "
                + "missing packets. AirPlay 1 senders choose the transmission buffer itself; "
                + "a longer window recovers more dropouts, a shorter one finalizes sooner.");

        minTrackSeconds.setMin(0);
        minTrackSeconds.setMax(600);
        minTrackSeconds.setStepButtonsVisible(true);

        idleSeconds.setMin(1);
        idleSeconds.setMax(120);
        idleSeconds.setStepButtonsVisible(true);

        ffmpegPath.setHelperText("Command name or absolute path.");
        metadataEnrichment.setHelperText("Only fills empty album/genre/year/track/cover; sender values win.");
        metadataEnrichmentDeezer.setHelperText("Keyless fallback catalog for tag completion.");

        audiobookEnabled.setHelperText("Marks chapters and shows Create audiobook… in the library.");
        audiobookAutoAssemble.setHelperText("Off by default: check sources and metadata first. "
                + "On: assemble as soon as a book ends.");
        audiobookGenres.setHelperText("Comma separated, e.g. audiobook,hörbuch");
        audiobookLongTrackSeconds.setMin(0);
        audiobookLongTrackSeconds.setMax(7200);
        audiobookLongTrackSeconds.setStepButtonsVisible(true);
        audiobookLongTrackSeconds.setHelperText("0 = only use genre/media kind for detection.");
        audiobookIdleSeconds.setMin(30);
        audiobookIdleSeconds.setMax(7200);
        audiobookIdleSeconds.setStepButtonsVisible(true);
        audiobookIdleSeconds.setHelperText("Automatic mode only: how long to wait after the last chapter.");
        audiobookLookup.setHelperText("Sends album and artist to itunes.apple.com; no API key needed.");
        audiobookLookupCountry.setMaxLength(2);
        audiobookLookupCountry.setHelperText("Apple catalog country for lookups and tag completion.");
        audiobookLookupMusicBrainz.setHelperText("Keyless; 1 request/s, cached per album.");
        audiobookLookupContact.setHelperText("Recommended by MusicBrainz, e.g. an email address.");
        audiobookBitrateKbps.setItems(List.of(32, 48, 64, 80, 96, 128, 160, 192));
        audiobookBitrateKbps.setItemLabelGenerator(SettingsView::audiobookBitrateLabel);
        audiobookBitrateKbps.setHelperText("AAC bitrate for the assembled .m4b; "
                + "96 kbps is the usual spoken-word setting.");
        audiobookSplitLongTracks.setHelperText("Fallback for the dialog: long pauses (>2.5 s) and at "
                + "least 5 minutes per chapter.");
        audiobookCatalogChapters.setHelperText("Fetches chapter names and durations from Deezer "
                + "(MusicBrainz when enabled above) for the dialog and aligns them to the recording. "
                + "Falls back to pauses when no matching edition is found.");
        audiobookKeepChapters.setHelperText("Automatic mode only: uncheck to delete individual "
                + "chapters after the book is ready.");

        deviceName.setValueChangeMode(ValueChangeMode.LAZY);
    }

    private static String aacLabel(int kbps) {
        return switch (kbps) {
            case 64 -> "64 kbps · speech";
            case 96 -> "96 kbps · podcast";
            case 128 -> "128 kbps · standard";
            case 160 -> "160 kbps";
            case 192 -> "192 kbps · recommended (music)";
            case 224 -> "224 kbps";
            case 256 -> "256 kbps · very high";
            case 320 -> "320 kbps · maximum";
            default -> kbps + " kbps";
        };
    }

    private static String audiobookBitrateLabel(int kbps) {
        return switch (kbps) {
            case 32 -> "32 kbps · voice";
            case 48 -> "48 kbps · voice";
            case 64 -> "64 kbps · compact";
            case 80 -> "80 kbps";
            case 96 -> "96 kbps · recommended (spoken word)";
            case 128 -> "128 kbps · higher";
            case 160 -> "160 kbps";
            case 192 -> "192 kbps · maximum";
            default -> kbps + " kbps";
        };
    }

    private static String mp3Label(int quality) {
        return switch (quality) {
            case 0 -> "V0 · ~245 kbps (best VBR)";
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
            case 500 -> "0.5 s · fastest, few retries";
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
            case 160 -> "160 kbps";
            case 192 -> "192 kbps · recommended";
            case 224 -> "224 kbps";
            case 256 -> "256 kbps · high";
            case 320 -> "320 kbps · maximum";
            default -> kbps + " kbps";
        };
    }

    private void updateMp3Mode() {
        boolean vbr = !"cbr".equals(mp3Mode.getValue());
        mp3Quality.setVisible(vbr);
        mp3Bitrate.setVisible(!vbr);
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
        deviceName.setValue(settings.deviceName);
        port.setValue(settings.port);
        interfaceAddress.setValue(settings.interfaceAddress == null ? "" : settings.interfaceAddress);
        formats.setValue(new LinkedHashSet<>(settings.formats));
        selectPreset(aacBitrate, settings.aacBitrateKbps);
        mp3Mode.setValue(settings.mp3Mode);
        selectPreset(mp3Quality, settings.mp3Quality);
        selectPreset(mp3Bitrate, settings.mp3BitrateKbps);
        updateMp3Mode();
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
        audiobookLookupContact.setValue(settings.audiobookLookupContact == null
                ? "" : settings.audiobookLookupContact);
        libraryPath.setText("Library: " + library.libraryDir());
        ffmpegState.setText(state.ffmpegAvailable ? "ffmpeg available" : "ffmpeg not found");
    }

    private void save() {
        if (deviceName.isEmpty()) {
            Notification.show("Receiver name is required", 3000, Notification.Position.MIDDLE);
            return;
        }
        Set<String> selected = formats.getValue();
        if (selected == null || selected.isEmpty()) {
            Notification.show("Select at least one output format", 3000, Notification.Position.MIDDLE);
            return;
        }
        Settings next = settingsService.current().copy();
        next.deviceName = deviceName.getValue().trim();
        next.port = port.getValue() == null ? 0 : port.getValue();
        next.interfaceAddress = interfaceAddress.getValue() == null ? "" : interfaceAddress.getValue().trim();
        next.formats = new java.util.ArrayList<>(List.copyOf(selected));
        next.aacBitrateKbps = aacBitrate.getValue() == null ? 192 : aacBitrate.getValue();
        next.mp3Quality = mp3Quality.getValue() == null ? 2 : mp3Quality.getValue();
        next.mp3Mode = mp3Mode.getValue() == null ? "vbr" : mp3Mode.getValue();
        next.mp3BitrateKbps = mp3Bitrate.getValue() == null ? 192 : mp3Bitrate.getValue();
        next.packetRecoveryMs = packetRecovery.getValue() == null ? 2500 : packetRecovery.getValue();
        next.minTrackSeconds = minTrackSeconds.getValue() == null ? 3 : minTrackSeconds.getValue();
        next.idleFinalizeSeconds = idleSeconds.getValue() == null ? 6 : idleSeconds.getValue();
        next.keepRaw = keepRaw.getValue();
        next.metadataEnrichmentEnabled = metadataEnrichment.getValue();
        next.metadataEnrichmentDeezer = metadataEnrichmentDeezer.getValue();
        next.ffmpegPath = ffmpegPath.getValue() == null || ffmpegPath.getValue().isBlank()
                ? "ffmpeg" : ffmpegPath.getValue().trim();
        next.audiobookEnabled = audiobookEnabled.getValue();
        next.audiobookAutoAssemble = audiobookAutoAssemble.getValue();
        next.audiobookGenres = audiobookGenres.getValue() == null ? "" : audiobookGenres.getValue().trim();
        next.audiobookLongTrackSeconds = audiobookLongTrackSeconds.getValue() == null
                ? 0 : audiobookLongTrackSeconds.getValue();
        next.audiobookIdleSeconds = audiobookIdleSeconds.getValue() == null
                ? 300 : audiobookIdleSeconds.getValue();
        next.audiobookBitrateKbps = audiobookBitrateKbps.getValue() == null
                ? 96 : audiobookBitrateKbps.getValue();
        next.audiobookKeepChapters = audiobookKeepChapters.getValue();
        next.audiobookSplitLongTracks = audiobookSplitLongTracks.getValue();
        next.audiobookCatalogChapters = audiobookCatalogChapters.getValue();
        next.audiobookLookupEnabled = audiobookLookup.getValue();
        next.audiobookLookupCountry = audiobookLookupCountry.getValue() == null
                ? "US" : audiobookLookupCountry.getValue().trim();
        next.audiobookLookupMusicBrainz = audiobookLookupMusicBrainz.getValue();
        next.audiobookLookupContact = audiobookLookupContact.getValue() == null
                ? "" : audiobookLookupContact.getValue().trim();

        try {
            Settings applied = settingsService.apply(next);
            load(applied);
            Notification.show("Settings saved", 2500, Notification.Position.BOTTOM_START);
        } catch (Exception e) {
            Notification.show("Could not save: " + e.getMessage(), 5000, Notification.Position.MIDDLE);
        }
    }
}
