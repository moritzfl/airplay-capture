package de.moritzf.airplaycapture.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small formatting helpers for the Vaadin views. */
final class Fmt {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(
        "dd.MM.yyyy",
        Locale.ROOT
    );
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private Fmt() {}

    static String duration(long millis) {
        if (millis <= 0) {
            return "0:00";
        }
        long seconds = Math.round(millis / 1000.0);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    static String bytes(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        String[] units = { "B", "KB", "MB", "GB" };
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(
            Locale.ROOT,
            value >= 10 || unit == 0 ? "%.0f %s" : "%.1f %s",
            value,
            units[unit]
        );
    }

    static String date(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** Time of day for today, otherwise date and time; for "finished at" labels. */
    static String dateTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        var when = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
        String time = TIME.format(when);
        return when.toLocalDate().equals(java.time.LocalDate.now(ZoneId.systemDefault()))
            ? time
            : DATE.format(when) + " " + time;
    }

    static String dateRange(long firstEpochMillis, long lastEpochMillis) {
        if (firstEpochMillis <= 0 && lastEpochMillis <= 0) {
            return "";
        }
        if (firstEpochMillis <= 0) {
            return date(lastEpochMillis);
        }
        if (lastEpochMillis <= 0) {
            return date(firstEpochMillis);
        }
        String first = date(firstEpochMillis);
        String last = date(lastEpochMillis);
        if (first.equals(last)) {
            return first;
        }
        if (first.substring(6).equals(last.substring(6))) {
            return first.substring(0, 5) + " – " + last;
        }
        return first + " – " + last;
    }
}
