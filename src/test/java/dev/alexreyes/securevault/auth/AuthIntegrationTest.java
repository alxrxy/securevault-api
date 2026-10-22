package dev.alexreyes.securevault.auth;

import dev.alexreyes.securevault.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void registerCreatesUserWithNormalizedEmailAndNoPasswordHash() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(postJson("/api/auth/register", credentialsJson(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void registerRejectsDuplicateEmailCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(postJson("/api/auth/register", credentialsJson(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void registerRejectsInvalidInput() throws Exception {
        mockMvc.perform(postJson("/api/auth/register", credentialsJson("not-an-email", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("email")));

        mockMvc.perform(postJson("/api/auth/register", credentialsJson(uniqueEmail(), "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("password")));

        mockMvc.perform(postJson("/api/auth/register", "{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void registerRejectsPasswordOverBcryptByteLimit() throws Exception {
        // 30 characters but 90 bytes in UTF-8 - over BCrypt's 72-byte limit.
        String multiByte = "€".repeat(30);

        mockMvc.perform(postJson("/api/auth/register", credentialsJson(uniqueEmail(), multiByte)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsBearerToken() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(postJson("/api/auth/login", credentialsJson(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(postJson("/api/auth/login", credentialsJson(email, "wrong-password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        mockMvc.perform(postJson("/api/auth/login", credentialsJson(uniqueEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void loginWithOverlongPasswordIsRejectedNotServerError() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(postJson("/api/auth/login", credentialsJson(email, "x".repeat(200))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUserWithValidToken() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginForToken(email);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownProtectedPathRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
