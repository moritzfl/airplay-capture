package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.shared.Registration;
import de.moritzf.airplaycapture.UiState;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Fans out state changes to attached Vaadin views and ticks once per second while recording. */
@Component
public class UiBroadcaster {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
            Thread thread = new Thread(runnable, "ui-broadcast");
            thread.setDaemon(true);
            return thread;
        }
    );

    public UiBroadcaster(UiState state) {
        state.addListener(this::fire);
        scheduler.scheduleAtFixedRate(
            () -> {
                if (state.receiving) {
                    fire();
                }
            },
            1,
            1,
            TimeUnit.SECONDS
        );
    }

    public Registration register(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void fire() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // a detached view must not break the others
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
