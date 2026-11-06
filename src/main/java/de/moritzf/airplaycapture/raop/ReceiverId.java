package de.moritzf.airplaycapture.raop;

import de.moritzf.airplaycapture.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * The receiver's AirPlay device ID: the six bytes in front of the advertised name and in the
 * Apple-Challenge response.
 *
 * <p>It is generated once per installation and kept in the data folder instead of using the
 * network card's address. Senders remember receivers by this ID, so two installations on one
 * machine (for example an old and a new package side by side) would otherwise share an identity,
 * and senders refuse to connect to the one they consider stale.
 */
public final class ReceiverId {

    private ReceiverId() {}

    /** Reads the ID from {@code file}, or creates and stores a new one. */
    public static byte[] load(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] stored = parse(Files.readString(file).trim());
                if (stored != null) {
                    return stored;
                }
                Log.warn("raop", "ignoring invalid receiver id in " + file);
            }
            byte[] created = generate(new SecureRandom());
            Files.writeString(file, format(created) + "\n");
            Log.info("raop", "created receiver id " + format(created));
            return created;
        } catch (IOException e) {
            byte[] fallback = generate(new SecureRandom());
            Log.warn(
                "raop",
                "cannot store receiver id in " +
                    file +
                    " (" +
                    e.getMessage() +
                    "); using " +
                    format(fallback) +
                    " until the next restart"
            );
            return fallback;
        }
    }

    /** A random, locally administered unicast address, so it never equals a real card's. */
    static byte[] generate(RandomGenerator random) {
        byte[] id = new byte[6];
        for (int i = 0; i < id.length; i++) {
            id[i] = (byte) random.nextInt(256);
        }
        id[0] = (byte) ((id[0] & 0xFC) | 0x02);
        return id;
    }

    static byte[] parse(String text) {
        if (text == null || !text.matches("[0-9A-Fa-f]{12}")) {
            return null;
        }
        return HexFormat.of().parseHex(text);
    }

    public static String format(byte[] id) {
        return HexFormat.of().formatHex(id).toUpperCase(Locale.ROOT);
    }
}
