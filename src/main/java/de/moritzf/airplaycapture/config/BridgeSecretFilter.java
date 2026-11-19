package de.moritzf.airplaycapture.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticate each bridge hop, including requests carrying an existing web session. */
final class BridgeSecretFilter extends OncePerRequestFilter {

    private final Path file;

    BridgeSecretFilter(Path file) {
        this.file = file;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException {
        String supplied = request.getHeader("X-Bridge-Secret");
        if (supplied != null && supplied.matches("[0-9a-f]{64}")) {
            String expected;
            try {
                expected = Files.readString(file).strip();
            } catch (IOException e) {
                response.setStatus(503);
                return;
            }
            if (
                MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII)
                )
            ) {
                chain.doFilter(request, response);
                return;
            }
        }
        response.setStatus(403);
    }
}
