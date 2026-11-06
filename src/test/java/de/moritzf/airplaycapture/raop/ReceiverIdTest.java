package de.moritzf.airplaycapture.raop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverIdTest {

    @TempDir
    Path temp;

    @Test
    void isCreatedOnceAndKeptAcrossRestarts() throws Exception {
        Path file = temp.resolve("receiver-id");
        byte[] first = ReceiverId.load(file);
        byte[] second = ReceiverId.load(file);

        assertEquals(6, first.length);
        assertArrayEquals(first, second);
        assertEquals(ReceiverId.format(first), Files.readString(file).trim());
    }

    @Test
    void eachInstallationGetsItsOwnId() {
        assertFalse(
            Arrays.equals(ReceiverId.load(temp.resolve("a")), ReceiverId.load(temp.resolve("b")))
        );
    }

    @Test
    void isALocallyAdministeredUnicastAddress() {
        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            byte first = ReceiverId.generate(random)[0];
            assertTrue((first & 0x02) != 0, "locally administered");
            assertEquals(0, first & 0x01, "unicast");
        }
    }

    @Test
    void replacesAnInvalidStoredId() throws Exception {
        Path file = temp.resolve("receiver-id");
        Files.writeString(file, "not-an-id\n");

        byte[] id = ReceiverId.load(file);

        assertEquals(ReceiverId.format(id), Files.readString(file).trim());
        assertNull(ReceiverId.parse("not-an-id"));
    }
}
