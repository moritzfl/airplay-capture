package de.moritzf.airplaycapture.util;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Resolves a sender IP to a human-readable LAN name, for example "iPhone-von-Moritz". */
public final class ClientNameResolver {

    private static final Pattern UUID = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\..*)?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final List<String> DEVICE_WORDS = List.of(
            "iphone", "ipad", "ipod", "macbook", "imac", "mac-", "android", "pixel",
            "galaxy", "phone", "laptop", "pc-", "desktop", "lylli", "audible");

    private ClientNameResolver() {
    }

    public static String resolve(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        return CACHE.computeIfAbsent(address, ClientNameResolver::lookup);
    }

    private static String lookup(String address) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(ptrNames(address));
        try {
            String fallback = InetAddress.getByName(address).getCanonicalHostName();
            if (fallback != null && !fallback.isBlank()) {
                candidates.add(fallback);
            }
        } catch (Exception ignored) {
            // keep whatever PTR gave us
        }
        return best(candidates);
    }

    static String best(List<String> candidates) {
        String best = "";
        int bestScore = 0;
        for (String candidate : candidates) {
            String raw = candidate == null ? "" : candidate.trim();
            if (UUID.matcher(raw).matches() || IPV4.matcher(raw).matches()) {
                continue;
            }
            String name = normalize(raw);
            if (name.isEmpty()) {
                continue;
            }
            int score = score(name);
            if (score > bestScore) {
                best = name;
                bestScore = score;
            }
        }
        return best;
    }

    private static int score(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (UUID.matcher(lower).matches() || IPV4.matcher(lower).matches()) {
            return 0;
        }
        int score = 1;
        for (String word : DEVICE_WORDS) {
            if (lower.contains(word)) {
                score += 10;
                break;
            }
        }
        return score;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String name = value.trim();
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (name.isBlank() || name.equalsIgnoreCase("localhost")) {
            return "";
        }
        return name;
    }

    private static List<String> ptrNames(String address) {
        List<String> names = new ArrayList<>();
        String reverse = reverseName(address);
        if (reverse == null) {
            return names;
        }
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(reverse, new String[]{"PTR"});
            Attribute ptr = attributes.get("PTR");
            if (ptr == null) {
                return names;
            }
            NamingEnumeration<?> values = ptr.getAll();
            while (values.hasMore()) {
                Object value = values.next();
                if (value != null) {
                    names.add(value.toString());
                }
            }
        } catch (Exception ignored) {
            // no PTR / DNS unavailable; caller falls back to getCanonicalHostName
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
        return names;
    }

    private static String reverseName(String address) {
        String[] parts = address == null ? new String[0] : address.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
    }
}
