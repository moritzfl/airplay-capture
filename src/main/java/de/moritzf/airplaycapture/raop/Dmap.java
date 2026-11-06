package de.moritzf.airplaycapture.raop;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parser for DMAP ("DMAP tagged") metadata blobs that AirPlay senders attach to
 * {@code SET_PARAMETER} requests. Format: repeated {4 byte tag, 4 byte big-endian length, data}.
 */
public final class Dmap {

    /** Containers whose contents are a list of further DMAP items. */
    private static final java.util.Set<String> CONTAINERS = java.util.Set.of("mlit", "mcon");

    private static final java.util.Set<String> NUMERIC = java.util.Set.of(
        "astn",
        "asyr",
        "asbr",
        "asdm",
        "astm",
        "mstn",
        "mikd",
        "mper"
    );

    private Dmap() {}

    public static Map<String, Object> parse(byte[] data) {
        Map<String, Object> result = new LinkedHashMap<>();
        parseInto(data, 0, data.length, result);
        return result;
    }

    private static void parseInto(byte[] data, int offset, int end, Map<String, Object> result) {
        int position = offset;
        while (position + 8 <= end) {
            String tag = new String(data, position, 4, StandardCharsets.US_ASCII);
            long lengthLong =
                ((long) (data[position + 4] & 0xFF) << 24) |
                ((data[position + 5] & 0xFF) << 16) |
                ((data[position + 6] & 0xFF) << 8) |
                (data[position + 7] & 0xFF);
            position += 8;
            if (lengthLong < 0 || lengthLong > end - position) {
                break;
            }
            int length = (int) lengthLong;
            if (CONTAINERS.contains(tag)) {
                parseInto(data, position, position + length, result);
            } else {
                result.putIfAbsent(tag, decode(tag, data, position, length));
            }
            position += length;
        }
    }

    private static Object decode(String tag, byte[] data, int offset, int length) {
        if (NUMERIC.contains(tag) && length > 0 && length <= 4) {
            int value = 0;
            for (int i = 0; i < length; i++) {
                value = (value << 8) | (data[offset + i] & 0xFF);
            }
            return value;
        }
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }

    public static String text(Map<String, Object> metadata, String tag) {
        Object value = metadata.get(tag);
        return value == null ? null : String.valueOf(value);
    }

    public static Integer number(Map<String, Object> metadata, String tag) {
        Object value = metadata.get(tag);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
