package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Element;

/**
 * Slim playback bar around a native {@code <audio>} element. Row buttons start playback
 * client-side so the browser still sees a user gesture (server roundtrips would lose it).
 */
final class AudioPlayer extends Div {

    private static final String PLAY_JS = """
    const audio = document.getElementById('app-audio');
    const title = document.getElementById('app-player-title');
    const formats = document.getElementById('app-player-formats');
    audio.src = this.dataset.src;
    title.textContent = this.dataset.title;
    formats.textContent = this.dataset.formats;
    audio.play();
    """;

    private final Element audio = new Element("audio");

    AudioPlayer() {
        addClassName("player-bar");
        setId("app-player");

        Div text = new Div();
        text.addClassName("player-text");
        Span label = new Span("Nothing playing");
        label.addClassName("player-title");
        label.setId("app-player-title");
        Span formats = new Span();
        formats.addClassName("player-formats");
        formats.setId("app-player-formats");
        text.add(label, formats);

        Div audioSlot = new Div();
        audioSlot.addClassName("player-audio");
        audio.setAttribute("controls", "");
        audio.setAttribute("preload", "none");
        audio.setAttribute("id", "app-audio");
        audio.setAttribute("aria-label", "Audio player");
        audioSlot.getElement().appendChild(audio);

        add(text, audioSlot);
    }

    Button playButton(String url, String title, String formats) {
        Button play = new Button(VaadinIcon.PLAY.create());
        play.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        play.addClassName("row-action");
        play.getElement().setAttribute("title", "Play");
        play.getElement().setAttribute("aria-label", "Play " + title);
        play.getElement().setAttribute("data-src", url);
        play.getElement().setAttribute("data-title", title);
        play.getElement().setAttribute("data-formats", formats);
        play.getElement().setAttribute("onclick", PLAY_JS);
        return play;
    }
}
