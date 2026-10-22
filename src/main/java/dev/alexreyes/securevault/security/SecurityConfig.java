package dev.alexreyes.securevault.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security configuration.
 *
 * <p>Protected by default: every endpoint requires authentication unless it
 * is explicitly listed in {@link #PUBLIC_ENDPOINTS}. New public endpoints
 * (register, login) get added here deliberately rather than defaulting open.
 *
 * <p><b>Why CSRF is disabled:</b> this API is stateless and authenticates
 * every request via a JWT in the {@code Authorization} header (added in the
 * auth milestone), not a session cookie. CSRF is an attack where a browser
 * automatically attaches a credential (a cookie) to a request the victim
 * didn't intend to send. A bearer token in a header is never attached
 * automatically by a browser, so that attack doesn't apply here. If a
 * browser-based client is ever added that stores the token in a cookie,
 * CSRF protection must be re-enabled for that flow.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**",
            "/api/auth/register",
            "/api/auth/login"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                );
        // TODO (registration + JWT auth milestone): wire in the JWT auth
        // filter here with http.addFilterBefore(jwtAuthFilter,
        // UsernamePasswordAuthenticationFilter.class) once it exists.
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
