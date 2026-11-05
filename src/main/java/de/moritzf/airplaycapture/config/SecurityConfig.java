package de.moritzf.airplaycapture.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Optional HTTP form login. Without {@code AIRPLAY_CAPTURE_WEB_PASSWORD} the interface is open
 * (LAN use); with it, every page and API call requires a session.
 *
 * <p>CSRF is disabled because the UI is a same-origin Vaadin application and the API is meant for
 * local integrations. Keep the port on a trusted network.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${airplay-capture.web.user:recorder}") String user,
            @Value("${airplay-capture.web.password:}") String password) {
        if (password == null || password.isBlank()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(user)
                .password("{noop}" + password)
                .roles("USER")
                .build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${airplay-capture.web.password:}") String password) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        if (password == null || password.isBlank()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/login", "/VAADIN/**", "/favicon.ico", "/error").permitAll()
                            .anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults())
                    .logout(Customizer.withDefaults());
        }
        return http.build();
    }
}
