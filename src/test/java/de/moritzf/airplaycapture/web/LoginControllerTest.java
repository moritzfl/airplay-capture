package de.moritzf.airplaycapture.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class LoginControllerTest {

    @Test
    void rendersAnEscapedTokenAndDoesNotCacheTheForm() throws Exception {
        var response = new LoginController().login(
            new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "a\"<b>"),
            null,
            null
        );
        assertEquals("no-store", response.getHeaders().getCacheControl());
        String html = response.getBody();
        assertTrue(html.contains("name=\"_csrf\" value=\"a&quot;&lt;b&gt;\""));
        assertFalse(html.contains("{{"));
    }

    @Test
    void rendersFixedFeedbackRatherThanReflectingQueryParameters() throws Exception {
        var controller = new LoginController();
        var csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token");
        String failed = controller.login(csrf, "<example>", null).getBody();
        assertTrue(failed.contains("role=\"alert\">Username or password is incorrect."));
        assertFalse(failed.contains("<example>"));
        assertTrue(controller.login(csrf, null, "").getBody().contains("You signed out."));
    }

    @Test
    void logoutFormUsesPostAndCsrf() throws Exception {
        var response = new LoginController().logout(
            new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token")
        );
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getBody().contains("<form method=\"post\" action=\"logout\">"));
        assertTrue(response.getBody().contains("name=\"_csrf\" value=\"token\""));
    }
}
