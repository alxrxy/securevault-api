package dev.alexreyes.securevault.security;

import dev.alexreyes.securevault.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRateLimitTest extends IntegrationTestBase {

    @Test
    void loginAndRegisterShareAPerIpLimitThenReturn429() throws Exception {
        String ip = randomIp();
        String badLogin = credentialsJson(uniqueEmail(), "wrong-password-123");

        for (int i = 0; i < AUTH_REQUESTS_PER_MINUTE - 1; i++) {
            mockMvc.perform(postJson("/api/auth/login", badLogin).with(fromIp(ip)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(postJson("/api/auth/register", credentialsJson(uniqueEmail(), PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isCreated());

        mockMvc.perform(postJson("/api/auth/login", badLogin).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));
        mockMvc.perform(postJson("/api/auth/register", credentialsJson(uniqueEmail(), PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void forwardedForHeaderDoesNotBypassLimit() throws Exception {
        String ip = randomIp();
        String badLogin = credentialsJson(uniqueEmail(), "wrong-password-123");

        for (int i = 0; i < AUTH_REQUESTS_PER_MINUTE; i++) {
            mockMvc.perform(postJson("/api/auth/login", badLogin).with(fromIp(ip))
                            .header("X-Forwarded-For", randomIp()))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(postJson("/api/auth/login", badLogin).with(fromIp(ip))
                        .header("X-Forwarded-For", randomIp()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void limitIsPerIp() throws Exception {
        String exhaustedIp = randomIp();
        String badLogin = credentialsJson(uniqueEmail(), "wrong-password-123");
        for (int i = 0; i <= AUTH_REQUESTS_PER_MINUTE; i++) {
            mockMvc.perform(postJson("/api/auth/login", badLogin).with(fromIp(exhaustedIp)));
        }

        mockMvc.perform(postJson("/api/auth/login", badLogin).with(fromIp(randomIp())))
                .andExpect(status().isUnauthorized());
    }
}
