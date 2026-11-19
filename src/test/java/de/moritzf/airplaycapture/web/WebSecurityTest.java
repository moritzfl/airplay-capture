package de.moritzf.airplaycapture.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.moritzf.airplaycapture.config.SecurityConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class WebSecurityTest {

    @Configuration
    @EnableWebSecurity
    static class WebSecurity {}

    @Test
    void passwordProtectsPagesAndMediaButAllowsAssets() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "airplay-capture.web.password=test-only"
            );
            context.register(WebSecurity.class, SecurityConfig.class);
            context.refresh();
            var mvc = MockMvcBuilders.standaloneSetup(new LoginController())
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
            mvc.perform(get("/app/")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/api/now/cover")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/VAADIN/test.js")).andExpect(status().isNotFound());
            mvc.perform(post("/logout")).andExpect(status().isForbidden());
        }
    }
}
