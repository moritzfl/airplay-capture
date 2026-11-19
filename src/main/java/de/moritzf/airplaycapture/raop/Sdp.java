package de.moritzf.airplaycapture.raop;

import de.moritzf.airplaycapture.media.AlacConfig;
import java.util.Arrays;
import tools.jackson.core.Base64Variants;

/**
 * Parsed ANNOUNCE payload: the SDP description a sender uses to announce the audio stream and the
 * per-session AES key.
 */
public final class Sdp {

    private final AlacConfig alac;
    private final byte[] encryptedAesKey;
    private final byte[] aesIv;

    private Sdp(AlacConfig alac, byte[] encryptedAesKey, byte[] aesIv) {
        this.alac = alac;
        this.encryptedAesKey = encryptedAesKey;
        this.aesIv = aesIv;
    }

    public AlacConfig alac() {
        return alac;
    }

    public byte[] encryptedAesKey() {
        return encryptedAesKey;
    }

    public byte[] aesIv() {
        return aesIv;
    }

    public boolean isEncrypted() {
        return encryptedAesKey != null && aesIv != null;
    }

    public static Sdp parse(String sdp) {
        if (sdp.length() > 16384) throw new IllegalArgumentException("SDP too large");
        AlacConfig alac = null;
        byte[] key = null;
        byte[] iv = null;
        for (String rawLine : sdp.split("\r?\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("a=")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String attribute = line.substring(2, colon);
            String value = line.substring(colon + 1).trim();
            switch (attribute) {
                case "fmtp" -> alac = parseFmtp(value);
                case "rsaaeskey" -> key = decodeBase64(value);
                case "aesiv" -> iv = decodeBase64(value);
                default -> {
                }
            }
        }
        if (alac == null) {
            // Fall back to the standard AirPlay ALAC configuration.
            alac = new AlacConfig(352, 0, 16, 40, 10, 14, 2, 255, 0, 0, 44100);
        }
        return new Sdp(alac, key, iv);
    }

    private static AlacConfig parseFmtp(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 11 && parts.length != 12) {
            throw new IllegalArgumentException("Invalid ALAC parameters");
        }
        // The first token may be the RTP payload type ("96").
        int start = parts.length - 11;
        try {
            int[] v = new int[11];
            for (int i = 0; i < 11; i++) {
                v[i] = Integer.parseInt(parts[start + i]);
            }
            if (
                v[0] < 1 ||
                v[0] > 8192 ||
                !(v[2] == 16 || v[2] == 20 || v[2] == 24 || v[2] == 32) ||
                v[6] < 1 ||
                v[6] > 2 ||
                v[8] < 0 ||
                v[8] > 65536 ||
                v[10] < 8000 ||
                v[10] > 192000 ||
                v[1] != 0 ||
                v[3] < 0 ||
                v[3] > 255 ||
                v[4] < 0 ||
                v[4] > 255 ||
                v[5] < 0 ||
                v[5] > 255 ||
                v[7] < 0 ||
                v[7] > 65535 ||
                v[9] < 0
            ) throw new IllegalArgumentException("Unsupported ALAC parameters");
            return new AlacConfig(
                v[0],
                v[1],
                v[2],
                v[3],
                v[4],
                v[5],
                v[6],
                v[7],
                v[8],
                v[9],
                v[10]
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ALAC parameters", e);
        }
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64Variants.MIME_NO_LINEFEEDS.withPaddingAllowed().decode(value);
        } catch (IllegalArgumentException e) {
            try {
                return Base64Variants.MODIFIED_FOR_URL.withPaddingAllowed().decode(value);
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    @Override
    public String toString() {
        return (
            "Sdp[alac=" +
            (alac == null ? "none" : alac) +
            ", encrypted=" +
            isEncrypted() +
            ", iv=" +
            (aesIv == null ? "none" : Arrays.toString(aesIv)) +
            "]"
        );
    }
}
