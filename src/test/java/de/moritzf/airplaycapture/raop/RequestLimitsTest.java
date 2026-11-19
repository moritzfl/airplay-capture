package de.moritzf.airplaycapture.raop;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestLimitsTest {

    @Test
    void rejectsOverBudgetRequestsBeforeReadingTheBody() {
        for (String header : java.util.List.of(
            "X-Header: " + "a".repeat(4096),
            "Content-Length: 33",
            "Content-Length: -1"
        )) {
            byte[] request = ("OPTIONS * RTSP/1.0\r\n" + header + "\r\n\r\n").getBytes(
                StandardCharsets.US_ASCII
            );
            assertThrows(IOException.class, () ->
                RtspConnection.read(
                    new BufferedInputStream(new ByteArrayInputStream(request)),
                    new ByteArrayOutputStream(),
                    32
                )
            );
        }
    }
}
