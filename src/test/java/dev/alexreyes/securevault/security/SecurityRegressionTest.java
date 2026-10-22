package dev.alexreyes.securevault.security;

import com.jayway.jsonpath.JsonPath;
import dev.alexreyes.securevault.IntegrationTestBase;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-cutting authn/authz and edge-case checks against PROJECT_SPEC.md,
 * run against every document endpoint rather than one representative.
 */
class SecurityRegressionTest extends IntegrationTestBase {

    private static final byte[] PDF = "%PDF-1.7\n%security test\n".getBytes(StandardCharsets.ISO_8859_1);

    /** Ways a request can present a credential that must not authenticate. */
    enum BadCredential {
        NONE, EMPTY_BEARER, GARBAGE, LOWERCASE_SCHEME_GARBAGE, EXPIRED, SWAPPED_SUBJECT, WRONG_ISSUER,
        UNSIGNED, HS384, FOREIGN_KEY, NO_SUBJECT, UNKNOWN_USER, TOKEN_IN_QUERY_PARAM
    }

    private String aliceToken;
    private UUID aliceId;
    private UUID aliceDocument;

    @BeforeEach
    void aliceHasADocument() throws Exception {
        aliceToken = newUserToken();
        aliceId = TestTokens.subjectOf(aliceToken);
        aliceDocument = upload(aliceToken, "alice.pdf");
    }

    @ParameterizedTest
    @EnumSource(BadCredential.class)
    void everyDocumentEndpointRejectsInvalidCredentials(BadCredential credential) throws Exception {
        String malloryToken = newUserToken();
        String header = authorizationHeader(credential, malloryToken);

        for (AbstractMockHttpServletRequestBuilder<?> request : documentRequests(aliceDocument)) {
            if (credential == BadCredential.TOKEN_IN_QUERY_PARAM) {
                request.param("access_token", malloryToken);
            } else if (header != null) {
                request.header("Authorization", header);
            }
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        // Nothing changed: Alice still has exactly her one document.
        mockMvc.perform(get("/api/documents").header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(aliceDocument.toString()));
    }

    @Test
    void anotherUsersDocumentIsUnreachableOnEveryEndpoint() throws Exception {
        String malloryToken = newUserToken();
        upload(malloryToken, "mallory.pdf");

        mockMvc.perform(get("/api/documents/" + aliceDocument).header("Authorization", bearer(malloryToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/" + aliceDocument + "/download").header("Authorization", bearer(malloryToken)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("security test"))));
        mockMvc.perform(delete("/api/documents/" + aliceDocument).header("Authorization", bearer(malloryToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents").header("Authorization", bearer(malloryToken)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].filename").value("mallory.pdf"));

        // Uppercase spelling of the same UUID is the same id - still not Mallory's.
        mockMvc.perform(get("/api/documents/" + aliceDocument.toString().toUpperCase())
                        .header("Authorization", bearer(malloryToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/" + aliceDocument.toString().toUpperCase())
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
    }

    @Test
    void uploadOwnerComesFromTokenNotFromRequest() throws Exception {
        String malloryToken = newUserToken();

        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "planted.pdf", "application/pdf", PDF))
                        .param("ownerId", aliceId.toString())
                        .param("owner_id", aliceId.toString())
                        .header("Authorization", bearer(malloryToken)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID planted = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(get("/api/documents/" + planted).header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/" + planted).header("Authorization", bearer(malloryToken)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-0000-0000-00000000000g",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "%27%20OR%201%3D1--", "00000000-0000-0000-0000-000000000000"})
    void malformedOrUnknownIdsAreClientErrorsOnEveryEndpoint(String id) throws Exception {
        // URI.create keeps the path raw; the String overloads would re-encode '%'.
        for (MockHttpServletRequestBuilder request : List.of(
                get(URI.create("/api/documents/" + id)),
                get(URI.create("/api/documents/" + id + "/download")),
                delete(URI.create("/api/documents/" + id)))) {
            mockMvc.perform(request.header("Authorization", bearer(aliceToken)))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.status").exists());
        }
    }

    @Test
    void malformedUploadsAreClientErrors() throws Exception {
        // Wrong part name.
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("document", "a.pdf", "application/pdf", PDF))
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isBadRequest());
        // Not multipart at all.
        mockMvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isUnsupportedMediaType());
        // Empty file / empty filename / filename made only of control chars and separators.
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]))
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "\r\n/../\u0000", "application/pdf", PDF))
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("document.pdf"));
    }

    @Test
    void serverErrorsDoNotLeakInternals() throws Exception {
        // Remove the stored file behind the database's back, then download.
        try (var files = Files.walk(STORAGE_ROOT.resolve(aliceId.toString()))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }

        mockMvc.perform(get("/api/documents/" + aliceDocument + "/download").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString(aliceId.toString() + "/"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString(STORAGE_ROOT.getFileName().toString()))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/%6Cogin", "/api/auth/login/", "/API/auth/login",
            "/api/auth//login", "/api/auth/./login", "/api/auth/login;x=1", "/api/%61uth/login"})
    void loginCannotBeReachedAroundTheRateLimit(String variant) throws Exception {
        String email = uniqueEmail();
        register(email);
        String ip = randomIp();
        for (int i = 0; i < AUTH_REQUESTS_PER_MINUTE; i++) {
            mockMvc.perform(postJson("/api/auth/login", credentialsJson(email, "wrong-password-123")).with(fromIp(ip)));
        }

        // Valid credentials through an alternative spelling of the path must
        // not log in once the canonical path is rate limited.
        mockMvc.perform(post(URI.create(variant)).contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().is(not(200)));
    }

    @Test
    void onlyHealthActuatorEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // The discovery index is reachable when authenticated but must list health only.
        mockMvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.health").exists())
                .andExpect(jsonPath("$._links.env").doesNotExist())
                .andExpect(jsonPath("$._links.beans").doesNotExist());
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump",
                "/actuator/configprops", "/actuator/loggers")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", bearer(aliceToken)))
                    .andExpect(status().isNotFound());
        }
    }

    private String authorizationHeader(BadCredential credential, String malloryToken) {
        UUID malloryId = TestTokens.subjectOf(malloryToken);
        return switch (credential) {
            case NONE, TOKEN_IN_QUERY_PARAM -> null;
            case EMPTY_BEARER -> "Bearer ";
            case GARBAGE -> "Bearer not.a.jwt";
            case LOWERCASE_SCHEME_GARBAGE -> "bearer x";
            case EXPIRED -> bearer(TestTokens.expired(malloryId));
            case SWAPPED_SUBJECT -> bearer(TestTokens.withSwappedSubject(malloryToken, aliceId));
            case WRONG_ISSUER -> bearer(TestTokens.validClaims(aliceId).issuer("evil").signWith(TestTokens.KEY, Jwts.SIG.HS256).compact());
            case UNSIGNED -> bearer(TestTokens.validClaims(aliceId).compact());
            case HS384 -> bearer(TestTokens.validClaims(aliceId).signWith(TestTokens.KEY, Jwts.SIG.HS384).compact());
            case FOREIGN_KEY -> bearer(TestTokens.validClaims(aliceId).signWith(Jwts.SIG.HS256.key().build(), Jwts.SIG.HS256).compact());
            case NO_SUBJECT -> bearer(Jwts.builder().issuer(TestTokens.ISSUER)
                    .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(3600)))
                    .signWith(TestTokens.KEY, Jwts.SIG.HS256).compact());
            case UNKNOWN_USER -> bearer(TestTokens.validClaims(UUID.randomUUID()).signWith(TestTokens.KEY, Jwts.SIG.HS256).compact());
        };
    }

    private static List<AbstractMockHttpServletRequestBuilder<?>> documentRequests(UUID id) {
        return List.of(
                get("/api/documents"),
                get("/api/documents/" + id),
                get("/api/documents/" + id + "/download"),
                delete("/api/documents/" + id),
                multipart("/api/documents").file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF)));
    }

    private UUID upload(String token, String filename) throws Exception {
        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", PDF))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
