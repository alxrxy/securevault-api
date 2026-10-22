package dev.alexreyes.securevault;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full application against a real PostgreSQL (Flyway migrations
 * included). The container is started once per JVM and shared by every
 * test class, so the cached Spring context keeps pointing at a live DB.
 */
@SpringBootTest(properties = {
        "app.jwt.secret=" + IntegrationTestBase.JWT_SECRET,
        "app.rate-limit.auth.requests-per-minute=" + IntegrationTestBase.AUTH_REQUESTS_PER_MINUTE
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    /** Test-only key; never used outside the test suite. */
    protected static final String JWT_SECRET = "xw5vShwqRtHJ6aPEuBt4iQHnGSPLXg88dGrmXzxXtIiy9gbPKFg6X1sh3HOoFRPA";
    protected static final String PASSWORD = "correct-horse-battery";
    protected static final int AUTH_REQUESTS_PER_MINUTE = 5;

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Uploaded files land here during tests, never in the project's ./data. */
    protected static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("securevault-test-storage").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired
    protected MockMvc mockMvc;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    protected static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /**
     * Each call comes from a random client IP so tests don't share (and
     * exhaust) one auth rate-limit bucket. Chain {@code .with(fromIp(..))}
     * to pin a specific IP.
     */
    protected static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json).with(fromIp(randomIp()));
    }

    protected static String credentialsJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    protected void register(String email) throws Exception {
        mockMvc.perform(postJson("/api/auth/register", credentialsJson(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    protected String loginForToken(String email) throws Exception {
        String body = mockMvc.perform(postJson("/api/auth/login", credentialsJson(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** Registers a fresh user and returns a valid bearer token for them. */
    protected String newUserToken() throws Exception {
        String email = uniqueEmail();
        register(email);
        return loginForToken(email);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
