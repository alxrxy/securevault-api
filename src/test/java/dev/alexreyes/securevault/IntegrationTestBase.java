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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full application against a real PostgreSQL (Flyway migrations
 * included). The container is started once per JVM and shared by every
 * test class, so the cached Spring context keeps pointing at a live DB.
 */
@SpringBootTest(properties = "app.jwt.secret=" + IntegrationTestBase.JWT_SECRET)
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    /** Test-only key; never used outside the test suite. */
    protected static final String JWT_SECRET = "xw5vShwqRtHJ6aPEuBt4iQHnGSPLXg88dGrmXzxXtIiy9gbPKFg6X1sh3HOoFRPA";
    protected static final String PASSWORD = "correct-horse-battery";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json);
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
}
