package de.moritzf.airplaycapture;

import de.moritzf.airplaycapture.util.WebPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Spring Boot entry point; keeps the previous {@code --data} / {@code --web-port} options. */
@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        List<String> passthrough = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                passthrough.add(argument);
                continue;
            }
            String key = argument.substring(2);
            String value = null;
            boolean inline = false;
            int equals = key.indexOf('=');
            if (equals >= 0) {
                value = key.substring(equals + 1);
                key = key.substring(0, equals);
                inline = true;
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[i + 1];
            }
            if (value != null && key.equals("data")) {
                passthrough.add("--airplay-capture.data=" + value);
                if (!inline) {
                    i++;
                }
            } else if (value != null && key.equals("web-port")) {
                passthrough.add("--server.port=" + value);
                if (!inline) {
                    i++;
                }
            } else {
                passthrough.add(argument);
            }
        }

        applyWebPort(passthrough);
        SpringApplication.run(Main.class, passthrough.toArray(String[]::new));
    }

    /**
     * Uses a free port instead of failing when the configured port is taken. This also covers a
     * direct {@code AIRPLAY_CAPTURE_WEB_PORT} environment value, not only the {@code --web-port}
     * option.
     */
    private static void applyWebPort(List<String> args) {
        int index = -1;
        String requested = null;
        for (int i = 0; i < args.size(); i++) {
            String argument = args.get(i);
            if (argument.startsWith("--server.port=")) {
                index = i;
                requested = argument.substring("--server.port=".length());
            }
        }
        if (requested == null) {
            requested = readPortFile(System.getenv("AIRPLAY_CAPTURE_WEB_PORT_FILE"));
        }
        if (requested == null) {
            requested = System.getenv("AIRPLAY_CAPTURE_WEB_PORT");
        }
        if (requested == null || requested.isBlank()) {
            requested = "8080";
        }
        int port;
        try {
            port = Integer.parseInt(requested.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (port <= 0) {
            return;
        }
        if (!WebPort.isFree(port)) {
            Log.info("app", "port " + port + " is busy; waiting up to 20 s for the previous "
                    + "container to release it");
        }
        int chosen = WebPort.pick(port, 20_000);
        if (chosen != port) {
            Log.warn("app", "port " + port + " is still in use, using " + chosen);
        }
        String value = "--server.port=" + chosen;
        if (index >= 0) {
            args.set(index, value);
        } else {
            args.add(value);
        }
    }

    /** Reads a port chosen by the installer; returns {@code null} when the file is absent or invalid. */
    private static String readPortFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            String value = Files.readString(Path.of(path)).trim();
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? value : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
