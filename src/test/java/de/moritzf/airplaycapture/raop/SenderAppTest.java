package de.moritzf.airplaycapture.raop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SenderAppTest {

    @Test
    void readsTheAppNameFromTheAirPlayUserAgent() {
        assertEquals("Audible", RtspConnection.appFromUserAgent("AirPlay/425.1 Audible/1.2"));
        assertEquals("Apple Books", RtspConnection.appFromUserAgent("AirPlay/425.1 Apple Books/3.0"));
        assertEquals("", RtspConnection.appFromUserAgent("AirPlay/980.77.5"));
        assertEquals("", RtspConnection.appFromUserAgent(null));
    }
}
