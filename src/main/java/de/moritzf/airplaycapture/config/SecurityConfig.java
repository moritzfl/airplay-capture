package de.moritzf.airplaycapture.config;

import com.vaadin.flow.server.HandlerHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Optional HTTP form login. Without {@code AIRPLAY_CAPTURE_WEB_PASSWORD} the interface is open
 * (LAN use); with it, application pages and media require a session.
 *
 * <p>Vaadin's internal requests use Flow's own CSRF protection; other writes use Spring Security.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public UserDetailsService userDetailsService(
        @Value("${airplay-capture.web.user:recorder}") String user,
        @Value("${airplay-capture.web.password:}") String password,
        PasswordEncoder encoder
    ) {
        if (password == null || password.isBlank()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(
            User.withUsername(user).password(encoder.encode(password)).roles("USER").build()
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        @Value("${airplay-capture.web.password:}") String password,
        @Value("${vaadin.url-mapping:/app/*}") String vaadinMapping
    ) throws Exception {
        http.headers(h ->
            h
                .contentSecurityPolicy(c -> c.policyDirectives("frame-ancestors 'none'"))
                .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "no-referrer"))
                .addHeaderWriter(
                    new StaticHeadersWriter(
                        "Permissions-Policy",
                        "camera=(), microphone=(), geolocation=()"
                    )
                )
        );
        http.csrf(csrf ->
            csrf.ignoringRequestMatchers(request ->
                HandlerHelper.isFrameworkInternalRequest(vaadinMapping, request)
            )
        );
        if (password == null || password.isBlank()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.addFilterBefore(new LoginThrottle(), UsernamePasswordAuthenticationFilter.class);
            http.authorizeHttpRequests(auth ->
                auth
                    .requestMatchers(
                        "/login.css",
                        "/icons/icon-64.png",
                        "/VAADIN/**",
                        "/favicon.ico",
                        "/error"
                    )
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            )
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/app/").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll());
        }
        return http.build();
    }
}
