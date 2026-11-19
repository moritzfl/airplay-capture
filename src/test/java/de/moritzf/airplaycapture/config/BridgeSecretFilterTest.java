package de.moritzf.airplaycapture.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class BridgeSecretFilterTest {

    @Test
    void onlyCurrentBridgeCredentialReachesApp(@TempDir Path data) throws Exception {
        Path secretFile = data.resolve(".bridge-secret");
        BridgeSecretFilter filter = new BridgeSecretFilter(secretFile);
        String secret = "ab".repeat(32);
        assertEquals(403, status(filter, null));
        assertEquals(503, status(filter, secret));
        Files.writeString(secretFile, secret + "\n");
        assertEquals(204, status(filter, secret));
        Files.writeString(secretFile, "cd".repeat(32));
        assertEquals(403, status(filter, secret));
    }

    private static int status(BridgeSecretFilter filter, String secret) throws Exception {
        var request = new MockHttpServletRequest("GET", "/app/");
        if (secret != null) request.addHeader("X-Bridge-Secret", secret);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(204));
        return response.getStatus();
    }
}
