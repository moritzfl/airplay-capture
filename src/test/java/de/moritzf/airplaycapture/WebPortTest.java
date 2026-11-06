package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.moritzf.airplaycapture.util.WebPort;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class WebPortTest {

    @Test
    void keepsAFreePort() throws Exception {
        int free;
        try (ServerSocket socket = new ServerSocket(0)) {
            free = socket.getLocalPort();
        }
        assertEquals(free, WebPort.pick(free));
    }

    @Test
    void skipsAPortThatIsInUse() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            int busy = socket.getLocalPort();
            assertNotEquals(busy, WebPort.pick(busy));
        }
    }

    @Test
    void waitsForAPortThatIsReleasedDuringStartup() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        int busy = socket.getLocalPort();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(300);
                socket.close();
            } catch (Exception ignored) {
                // test cleanup
            }
        });
        releaser.start();
        try {
            assertEquals(busy, WebPort.pick(busy, 3_000));
        } finally {
            releaser.join();
            socket.close();
        }
    }

    @Test
    void leavesAutoAndInvalidValuesAlone() {
        assertEquals(0, WebPort.pick(0));
        assertEquals(70000, WebPort.pick(70000));
    }
}
