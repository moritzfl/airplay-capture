package de.moritzf.airplaycapture.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Renders sign-in; Spring Security still owns authentication and logout. */
@RestController
public class LoginController {

    private final String loginPage;
    private final String logoutPage;

    public LoginController() throws IOException {
        loginPage = new ClassPathResource("login.html").getContentAsString(StandardCharsets.UTF_8);
        logoutPage = new ClassPathResource("logout.html").getContentAsString(
            StandardCharsets.UTF_8
        );
    }

    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> login(
        CsrfToken csrf,
        @RequestParam(name = "error", required = false) String error,
        @RequestParam(name = "logout", required = false) String logout
    ) {
        String notice =
            error != null
                ? "<p class=\"notice\" role=\"alert\">Username or password is incorrect.</p>"
                : logout != null
                  ? "<p class=\"notice\" role=\"status\">You signed out.</p>"
                  : "";
        return form(loginPage.replace("{{notice}}", notice), csrf);
    }

    @GetMapping(value = "/logout", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> logout(CsrfToken csrf) {
        return form(logoutPage, csrf);
    }

    private static ResponseEntity<String> form(String template, CsrfToken csrf) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                template
                    .replace("{{csrfName}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
                    .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
            );
    }
}
