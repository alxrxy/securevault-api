package dev.alexreyes.securevault.security;

import dev.alexreyes.securevault.user.entity.AppUser;
import dev.alexreyes.securevault.user.repository.AppUserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the {@code Authorization: Bearer <token>} header, validates the
 * token via {@link JwtService}, and - if valid - populates the
 * SecurityContext with an {@link AuthenticatedUser} principal. Runs once
 * per request, before the authorization rules in SecurityConfig decide
 * whether the request is allowed through.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());

        try {
            UUID userId = jwtService.validateAndGetUserId(token);
            Optional<AppUser> maybeUser = userRepository.findById(userId);

            if (maybeUser.isPresent()) {
                AppUser user = maybeUser.get();
                AuthenticatedUser principal =
                        new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // If the user no longer exists (e.g. deleted after the token
            // was issued), the context stays unauthenticated and the
            // downstream authorization rule rejects the request normally.
        } catch (JwtException | IllegalArgumentException e) {
            // Invalid signature, expired, wrong issuer, malformed subject,
            // etc. - all treated identically: leave the context
            // unauthenticated. We never tell the caller which check failed.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
