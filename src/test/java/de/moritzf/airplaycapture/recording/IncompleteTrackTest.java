package de.moritzf.airplaycapture.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncompleteTrackTest {

    @Test
    void marksClearlyShortRecordings() {
        assertTrue(RecordingManager.isIncomplete(60_000, 600_000));
        assertTrue(RecordingManager.isIncomplete(500_000, 600_000));
    }

    @Test
    void ignoresSmallDifferencesAndUnknownDurations() {
        assertFalse(RecordingManager.isIncomplete(599_000, 600_000));
        assertFalse(RecordingManager.isIncomplete(60_000, 0));
        assertFalse(RecordingManager.isIncomplete(0, 600_000));
        assertFalse(RecordingManager.isIncomplete(601_000, 600_000));
    }
}
