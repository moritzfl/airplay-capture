package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Element;

/**
 * Mini player docked to the bottom of the window, around a native {@code <audio>} element. It
 * lives in the application shell, so playback continues while moving between views, and it only
 * appears once something was started. Row buttons start playback client-side so the browser still
 * sees a user gesture (server roundtrips would lose it).
 */
final class AudioPlayer extends Div {

    /** A row button toggles its own recording: play, pause, or switch to another recording. */
    private static final String PLAY_JS = "window.appPlayer && window.appPlayer.toggle(this)";

    /**
     * Client-side player state. Row buttons are re-rendered by the server while a recording
     * plays, so the "playing" marker is re-applied whenever the page content changes.
     */
    private static final String INIT_JS = """
    const bar = this;
    if (bar.dataset.ready) return;
    bar.dataset.ready = 'true';
    const audio = bar.querySelector('audio');
    const title = bar.querySelector('.player-title');
    const formats = bar.querySelector('.player-formats');
    const absolute = (src) => new URL(src, document.baseURI).href;
    let current = '';
    let pending = false;
    const sync = () => {
      pending = false;
      const playing = current && !audio.paused && !audio.ended;
      document.querySelectorAll('.play-action').forEach((button) => {
        const active = playing && absolute(button.dataset.src) === current;
        if (button.classList.contains('is-playing') === Boolean(active)) return;
        button.classList.toggle('is-playing', Boolean(active));
        const icon = button.querySelector('vaadin-icon');
        if (icon) icon.setAttribute('icon', active ? 'vaadin:pause' : 'vaadin:play');
        const name = button.dataset.title || '';
        button.setAttribute('aria-label', (active ? 'Pause ' : 'Play ') + name);
        button.setAttribute('title', active ? 'Pause' : 'Play');
      });
    };
    const schedule = () => {
      if (!pending) {
        pending = true;
        requestAnimationFrame(sync);
      }
    };
    ['play', 'pause', 'ended', 'emptied'].forEach((name) => audio.addEventListener(name, schedule));
    new MutationObserver(schedule).observe(document.body, {childList: true, subtree: true});
    window.appPlayer = {
      toggle(button) {
        const src = absolute(button.dataset.src);
        if (src === current && !audio.ended) {
          if (audio.paused) audio.play(); else audio.pause();
          return;
        }
        current = src;
        audio.src = src;
        title.textContent = button.dataset.title || '';
        formats.textContent = button.dataset.formats || '';
        bar.classList.add('open');
        document.body.classList.add('player-open');
        audio.play();
      },
      close() {
        audio.pause();
        audio.removeAttribute('src');
        audio.load();
        current = '';
        bar.classList.remove('open');
        document.body.classList.remove('player-open');
        schedule();
      },
    };
    """;

    AudioPlayer() {
        addClassName("player-bar");
        setId("app-player");
        getElement().setAttribute("role", "region");
        getElement().setAttribute("aria-label", "Player");

        Div text = new Div();
        text.addClassName("player-text");
        Span label = new Span();
        label.addClassName("player-title");
        Span formats = new Span();
        formats.addClassName("player-formats");
        text.add(label, formats);

        Div audioSlot = new Div();
        audioSlot.addClassName("player-audio");
        Element audio = new Element("audio");
        audio.setAttribute("controls", "");
        audio.setAttribute("preload", "none");
        audio.setAttribute("id", "app-audio");
        audio.setAttribute("aria-label", "Audio player");
        audioSlot.getElement().appendChild(audio);

        Button close = new Button(VaadinIcon.CLOSE_SMALL.create());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        close.addClassName("player-close");
        close.getElement().setAttribute("title", "Stop and close the player");
        close.getElement().setAttribute("aria-label", "Stop and close the player");
        close.getElement().setAttribute("onclick", "window.appPlayer && window.appPlayer.close()");

        add(text, audioSlot, close);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(INIT_JS);
    }

    /** A play button for one recording; {@code detail} is the second line shown in the player. */
    static Button playButton(String url, String title, String detail) {
        Button play = new Button(VaadinIcon.PLAY.create());
        play.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        play.addClassNames("row-action", "play-action");
        play.getElement().setAttribute("title", "Play");
        play.getElement().setAttribute("aria-label", "Play " + title);
        play.getElement().setAttribute("data-src", url);
        play.getElement().setAttribute("data-title", title);
        play.getElement().setAttribute("data-formats", detail == null ? "" : detail);
        play.getElement().setAttribute("onclick", PLAY_JS);
        return play;
    }
}
