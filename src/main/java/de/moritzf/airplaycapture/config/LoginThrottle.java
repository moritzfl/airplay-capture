package de.moritzf.airplaycapture.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Counts attempts before password hashing, using the connection peer instead of a supplied header. */
final class LoginThrottle extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults().matcher(
        HttpMethod.POST,
        "/login"
    );
    private final Map<String, Window> peers = new HashMap<>();

    private record Window(long start, int count) {}

    private synchronized boolean admit(String peer) {
        long now = System.nanoTime();
        long minute = TimeUnit.MINUTES.toNanos(1);
        Window window = peers.get(peer);
        if (window == null || now - window.start >= minute) {
            if (peers.size() >= 1024) {
                peers.values().removeIf(w -> now - w.start >= minute);
                if (peers.size() >= 1024) return false;
            }
            window = new Window(now, 0);
        }
        if (window.count >= 10) return false;
        peers.put(peer, new Window(window.start, window.count + 1));
        return true;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req,
        HttpServletResponse res,
        FilterChain chain
    ) throws IOException, ServletException {
        if (LOGIN.matches(req) && !admit(req.getRemoteAddr())) {
            res.setHeader("Retry-After", "60");
            res.setStatus(429);
            return;
        }
        chain.doFilter(req, res);
    }
}
