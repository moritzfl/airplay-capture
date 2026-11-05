package de.moritzf.airplaycapture;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Small in-memory log with ring buffer and listeners, used by the web UI. */
public final class Log {

    public record Line(String time, String level, String scope, String message) {
    }

    private static final int MAX_LINES = 400;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<Line> LINES = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private Log() {
    }

    public static void info(String scope, String message) {
        write("INFO", scope, message, false);
    }

    public static void warn(String scope, String message) {
        write("WARN", scope, message, true);
    }

    public static void error(String scope, String message) {
        write("ERROR", scope, message, true);
    }

    public static void error(String scope, String message, Throwable t) {
        write("ERROR", scope, message + " (" + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage()) + ")", true);
    }

    private static void write(String level, String scope, String message, boolean stderr) {
        String time = LocalTime.now().format(TIME);
        Line line = new Line(time, level, scope, message);
        synchronized (LINES) {
            LINES.add(line);
            if (LINES.size() > MAX_LINES) {
                LINES.subList(0, LINES.size() - MAX_LINES).clear();
            }
        }
        String formatted = "%s %-5s %-10s %s".formatted(time, level, scope, message);
        if (stderr) {
            System.err.println(formatted);
        } else {
            System.out.println(formatted);
        }
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public static List<Line> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }
}
