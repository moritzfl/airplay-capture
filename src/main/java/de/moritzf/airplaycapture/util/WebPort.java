package de.moritzf.airplaycapture.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Picks a free TCP port for the web UI, so a busy port never prevents a start. */
public final class WebPort {

    private WebPort() {
    }

    /**
     * Returns {@code requested} when it is free, otherwise the next free port above it. {@code 0}
     * (let the server choose) and invalid values are returned unchanged.
     */
    public static int pick(int requested) {
        return pick(requested, 0);
    }

    /**
     * Like {@link #pick(int)}, but waits up to {@code waitMs} for the requested port to be released.
     * A package upgrade briefly overlaps the old and new containers, so the old one still owns the
     * port when the new one starts.
     */
    public static int pick(int requested, long waitMs) {
        if (requested <= 0 || requested > 65535) {
            return requested;
        }
        if (isFree(requested)) {
            return requested;
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (isFree(requested)) {
                return requested;
            }
        }
        for (int port = requested + 1; port <= 65535; port++) {
            if (isFree(port)) {
                return port;
            }
        }
        for (int port = 1024; port < requested; port++) {
            if (isFree(port)) {
                return port;
            }
        }
        return requested;
    }

    public static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            // Match the web server: TIME_WAIT sockets from health probes must not
            // look like a busy port, or a restart picks a port the bridge cannot reach.
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
