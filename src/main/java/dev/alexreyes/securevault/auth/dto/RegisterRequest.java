package dev.alexreyes.securevault.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 72) String password) {

    /**
     * BCrypt only accepts up to 72 bytes, and multi-byte characters can
     * exceed that within 72 characters. Rejecting here gives a 400 instead
     * of the encoder throwing.
     */
    @AssertTrue(message = "must be at most 72 bytes when UTF-8 encoded")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
