package dev.alexreyes.securevault.user.dto;

import dev.alexreyes.securevault.user.entity.AppUser;

import java.time.Instant;
import java.util.UUID;

/**
 * What we return for a user - deliberately excludes passwordHash. Never
 * build this from field access alone; always go through {@link #from}.
 */
public record UserResponse(UUID id, String email, String role, Instant createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
