package de.moritzf.airplaycapture.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientNameResolverTest {

    @Test
    void prefersDeviceNamesOverRouterUuids() {
        String best = ClientNameResolver.best(List.of(
                "17b2849f-1789-4548-991a-06ffb8d2a58a.fritz.box.",
                "bfd97326-1267-4037-9bf6-546f6f22f410.fritz.box.",
                "iPhone-von-Moritz.fritz.box."));
        assertEquals("iPhone-von-Moritz", best);
    }

    @Test
    void stripsDomainSuffix() {
        assertEquals("MacBook-Pro", ClientNameResolver.best(List.of("MacBook-Pro.local.")));
    }

    @Test
    void ignoresUuidsAndIpAddresses() {
        assertEquals("", ClientNameResolver.best(List.of(
                "17b2849f-1789-4548-991a-06ffb8d2a58a.fritz.box.",
                "192.168.178.155")));
    }
}
