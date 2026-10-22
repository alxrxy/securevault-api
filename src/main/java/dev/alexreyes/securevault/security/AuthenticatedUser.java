package dev.alexreyes.securevault.security;

import java.util.UUID;

/**
 * The authenticated principal placed in the SecurityContext by
 * {@link JwtAuthenticationFilter}. Controllers read this to know who is
 * making the request - notably {@code id}, which document ownership checks
 * in the next milestone compare directly against {@code documents.owner_id}
 * with no extra lookup needed.
 */
public record AuthenticatedUser(UUID id, String email, String role) {
}
