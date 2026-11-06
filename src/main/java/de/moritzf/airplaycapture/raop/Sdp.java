package de.moritzf.airplaycapture.raop;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;

/**
 * Parsed ANNOUNCE payload: the SDP description a sender uses to announce the audio stream and the
 * per-session AES key.
 */
public final class Sdp {

    /** ALAC parameters carried in the SDP {@code a=fmtp} line (the ALACSpecificConfig cookie). */
    public record AlacConfig(
        int frameLength,
        int compatibleVersion,
        int bitDepth,
        int pb,
        int mb,
        int kb,
        int channels,
        int maxRun,
        int maxFrameBytes,
        int avgBitRate,
        int sampleRate
    ) {
        public byte[] magicCookie() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(24);
            writeInt(out, frameLength);
            out.write(compatibleVersion);
            out.write(bitDepth);
            out.write(pb);
            out.write(mb);
            out.write(kb);
            out.write(channels);
            out.write((maxRun >> 8) & 0xFF);
            out.write(maxRun & 0xFF);
            writeInt(out, maxFrameBytes);
            writeInt(out, avgBitRate);
            writeInt(out, sampleRate);
            return out.toByteArray();
        }

        public int bytesPerFrame() {
            return (bitDepth / 8) * channels;
        }

        private static void writeInt(ByteArrayOutputStream out, int value) {
            out.write((value >> 24) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }

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
        if (parts.length < 11) {
            return null;
        }
        // The first token may be the RTP payload type ("96").
        int start = parts.length - 11;
        try {
            int[] v = new int[11];
            for (int i = 0; i < 11; i++) {
                v[i] = Integer.parseInt(parts[start + i]);
            }
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
            return null;
        }
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(value);
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
