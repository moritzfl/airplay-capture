package de.moritzf.airplaycapture.util;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Picks the LAN interface used for mDNS advertisement and RAOP sockets. */
public final class NetworkTools {

    public record InterfaceInfo(NetworkInterface networkInterface, InetAddress address, byte[] mac) {
    }

    private NetworkTools() {
    }

    public static InterfaceInfo pickInterface(String requested) throws IOException {
        if (requested != null && !requested.isBlank()) {
            InetAddress address = InetAddress.getByName(requested.trim());
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
            if (networkInterface == null) {
                throw new IOException("no interface for address " + requested);
            }
            return new InterfaceInfo(networkInterface, address, macOf(networkInterface, address));
        }
        // The default route usually points at the LAN interface, which is the only one AirPlay
        // clients can reach. This also copes with DSM's ovs_eth0/bond0 names.
        for (String name : defaultRouteInterfaces()) {
            if (bridgeLike(name)) {
                continue;
            }
            NetworkInterface networkInterface = NetworkInterface.getByName(name);
            if (networkInterface == null || !networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            InetAddress address = ipv4Of(networkInterface);
            if (address != null) {
                return new InterfaceInfo(networkInterface, address, macOf(networkInterface, address));
            }
        }

        List<NetworkInterface> candidates = new ArrayList<>();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isPointToPoint()) {
                continue;
            }
            if (bridgeLike(networkInterface.getName())) {
                continue;
            }
            if (ipv4Of(networkInterface) == null) {
                continue;
            }
            candidates.add(networkInterface);
        }
        candidates.sort((a, b) -> Integer.compare(score(b), score(a)));
        for (NetworkInterface networkInterface : candidates) {
            InetAddress address = ipv4Of(networkInterface);
            if (address != null) {
                return new InterfaceInfo(networkInterface, address, macOf(networkInterface, address));
            }
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(loopback);
        return new InterfaceInfo(networkInterface, loopback, macOf(networkInterface, loopback));
    }

    private static int score(NetworkInterface networkInterface) {
        String name = networkInterface.getName().toLowerCase();
        int score = 0;
        if (name.startsWith("en") || name.startsWith("eth") || name.startsWith("wlan")
                || name.startsWith("bond") || name.startsWith("ovs")) {
            score += 10;
        }
        try {
            if (networkInterface.isVirtual()) {
                score -= 5;
            }
            if (networkInterface.supportsMulticast()) {
                score += 3;
            }
        } catch (SocketException ignored) {
            // ignore
        }
        return score;
    }

    /** Interfaces created by Docker/VMs/VPNs; advertising on them would not reach the LAN. */
    private static boolean bridgeLike(String name) {
        String lower = name.toLowerCase();
        return lower.equals("lo") || lower.startsWith("docker") || lower.startsWith("br-")
                || lower.startsWith("veth") || lower.startsWith("virbr") || lower.startsWith("vmnet")
                || lower.startsWith("tun") || lower.startsWith("tap") || lower.startsWith("utun")
                || lower.startsWith("tailscale") || lower.startsWith("wg") || lower.startsWith("sit")
                || lower.startsWith("ip6tnl") || lower.startsWith("dummy");
    }

    /** Interface names that carry the IPv4 default route (Linux); empty on other platforms. */
    private static List<String> defaultRouteInterfaces() {
        Path route = Path.of("/proc/net/route");
        if (!Files.isReadable(route)) {
            return List.of();
        }
        List<String> interfaces = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(route);
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).trim().split("\\s+");
                if (parts.length < 8 || !"00000000".equals(parts[1])) {
                    continue;
                }
                int flags = Integer.parseInt(parts[3], 16);
                if ((flags & 0x2) != 0 && !interfaces.contains(parts[0])) {
                    interfaces.add(parts[0]);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return interfaces;
    }

    /** Short description of the interfaces, for startup diagnostics. */
    public static String describeInterfaces() {
        StringBuilder text = new StringBuilder();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp()) {
                    continue;
                }
                List<String> addresses = new ArrayList<>();
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        addresses.add(address.getHostAddress());
                    }
                }
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(networkInterface.getName()).append('=')
                        .append(addresses.isEmpty() ? "-" : String.join("/", addresses));
                if (bridgeLike(networkInterface.getName())) {
                    text.append("(bridge)");
                }
            }
        } catch (SocketException e) {
            return "unavailable: " + e.getMessage();
        }
        return text.toString();
    }

    private static InetAddress ipv4Of(NetworkInterface networkInterface) {
        for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
            if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                return address;
            }
        }
        return null;
    }

    private static byte[] macOf(NetworkInterface networkInterface, InetAddress address) {
        try {
            byte[] mac = networkInterface == null ? null : networkInterface.getHardwareAddress();
            if (mac != null && mac.length == 6) {
                return mac;
            }
        } catch (SocketException ignored) {
            // fall through
        }
        try {
            String seed = InetAddress.getLocalHost().getHostName() + "/" + address.getHostAddress();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] mac = new byte[6];
            System.arraycopy(hash, 0, mac, 0, 6);
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // locally administered, unicast
            return mac;
        } catch (Exception e) {
            return new byte[]{0x02, 0x00, 0x00, 0x00, 0x00, 0x01};
        }
    }
}
