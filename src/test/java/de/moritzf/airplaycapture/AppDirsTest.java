package de.moritzf.airplaycapture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppDirsTest {

    @Test
    void macUsesApplicationSupport() {
        assertEquals("/home/u/Library/Application Support/AirPlayCapture",
                AppDirs.defaultDataDir("mac os x", "/home/u", null));
    }

    @Test
    void windowsUsesAppData() {
        assertEquals("C:\\Users\\u\\AppData\\Roaming/AirPlayCapture",
                AppDirs.defaultDataDir("windows 11", "C:\\Users\\u", "C:\\Users\\u\\AppData\\Roaming"));
    }

    @Test
    void windowsFallsBackToTheRoamingFolderWhenAppDataIsUnset() {
        assertEquals("/home/u/AppData/Roaming/AirPlayCapture",
                AppDirs.defaultDataDir("windows 10", "/home/u", null));
    }

    @Test
    void linuxUsesADotDirectory() {
        assertEquals("/home/u/.airplay-capture",
                AppDirs.defaultDataDir("linux", "/home/u", null));
    }
}
