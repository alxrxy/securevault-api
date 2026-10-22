package dev.alexreyes.securevault.security;

import dev.alexreyes.securevault.user.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Baseline security configuration.
 *
 * <p>Protected by default: every endpoint requires authentication unless it
 * is explicitly listed in {@link #PUBLIC_ENDPOINTS}. New public endpoints
 * get added here deliberately rather than defaulting open.
 *
 * <p><b>Why CSRF is disabled:</b> this API is stateless and authenticates
 * every request via a JWT in the {@code Authorization} header, not a
 * session cookie. CSRF is an attack where a browser automatically attaches
 * a credential (a cookie) to a request the victim didn't intend to send. A
 * bearer token in a header is never attached automatically by a browser,
 * so that attack doesn't apply here. If a browser-based client is ever
 * added that stores the token in a cookie, CSRF protection must be
 * re-enabled for that flow.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**",
            "/api/auth/register",
            "/api/auth/login",
            // API documentation (springdoc). Describes the API only - every
            // operation it lists is still protected as configured here.
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            // Servlet error dispatches; without this a failed public request
            // would be re-reported as 401 by the error page itself.
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            AppUserRepository userRepository,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            JsonMapper jsonMapper,
            @Value("${app.rate-limit.auth.requests-per-minute}") long authRequestsPerMinute) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new AuthRateLimitFilter(authRequestsPerMinute, jsonMapper),
                        JwtAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Used by the login endpoint to check email + password. DaoAuthenticationProvider
     * reports unknown emails and wrong passwords identically (BadCredentialsException)
     * and runs a dummy hash for unknown emails to blunt timing-based enumeration.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
