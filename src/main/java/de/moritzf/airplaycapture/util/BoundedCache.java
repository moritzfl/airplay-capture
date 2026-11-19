package de.moritzf.airplaycapture.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BoundedCache {

    private BoundedCache() {}

    public static <K, V> Map<K, V> create() {
        return Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> entry) {
                    return size() > 256;
                }
            }
        );
    }
}
