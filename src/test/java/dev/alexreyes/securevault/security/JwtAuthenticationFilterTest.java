package dev.alexreyes.securevault.security;

import dev.alexreyes.securevault.IntegrationTestBase;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every invalid-token case must end in the same 401 - never a 500, and
 * never a hint about which check failed.
 */
class JwtAuthenticationFilterTest extends IntegrationTestBase {

    private static final SecretKey KEY = TestTokens.KEY;
    private static final String ISSUER = TestTokens.ISSUER;

    @Test
    void validTokenIsAccepted() throws Exception {
        String email = uniqueEmail();
        register(email);

        expectStatus(loginForToken(email), 200);
    }

    @Test
    void missingOrNonBearerHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        UUID userId = registeredUserId();
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String token = Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.HOURS)))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();

        expectStatus(token, 401);
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginForToken(email);

        // Swap the payload for one claiming a different subject, keeping the original signature.
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"iss\":\"" + ISSUER + "\",\"exp\":"
                        + (Instant.now().getEpochSecond() + 3600) + "}").getBytes());
        expectStatus(parts[0] + "." + forgedPayload + "." + parts[2], 401);

        // Flip a character in the signature.
        char last = token.charAt(token.length() - 2);
        String badSignature = token.substring(0, token.length() - 2) + (last == 'A' ? 'B' : 'A')
                + token.charAt(token.length() - 1);
        expectStatus(badSignature, 401);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        UUID userId = registeredUserId();
        SecretKey otherKey = Jwts.SIG.HS256.key().build();

        expectStatus(validClaims(userId).signWith(otherKey, Jwts.SIG.HS256).compact(), 401);
    }

    @Test
    void tokenWithDifferentAlgorithmIsRejectedEvenWithCorrectKey() throws Exception {
        UUID userId = registeredUserId();

        expectStatus(validClaims(userId).signWith(KEY, Jwts.SIG.HS384).compact(), 401);
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        UUID userId = registeredUserId();

        expectStatus(validClaims(userId).compact(), 401);
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        UUID userId = registeredUserId();
        String token = validClaims(userId).issuer("someone-else").signWith(KEY, Jwts.SIG.HS256).compact();

        expectStatus(token, 401);
    }

    @Test
    void missingOrMalformedSubjectIsRejectedNotServerError() throws Exception {
        String noSubject = Jwts.builder()
                .issuer(ISSUER)
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
        expectStatus(noSubject, 401);

        String badSubject = Jwts.builder()
                .subject("not-a-uuid")
                .issuer(ISSUER)
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
        expectStatus(badSubject, 401);
    }

    @Test
    void tokenForNonexistentUserIsRejected() throws Exception {
        expectStatus(validClaims(UUID.randomUUID()).signWith(KEY, Jwts.SIG.HS256).compact(), 401);
    }

    private UUID registeredUserId() throws Exception {
        return TestTokens.subjectOf(newUserToken());
    }

    private static io.jsonwebtoken.JwtBuilder validClaims(UUID userId) {
        return TestTokens.validClaims(userId);
    }

    private void expectStatus(String token, int expected) throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected));
    }
}
