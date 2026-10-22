package dev.alexreyes.securevault.security;

import dev.alexreyes.securevault.IntegrationTestBase;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Forges valid and deliberately invalid JWTs for security tests. */
final class TestTokens {

    static final SecretKey KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(IntegrationTestBase.JWT_SECRET));
    static final String ISSUER = "securevault-api";

    private TestTokens() {
    }

    static JwtBuilder validClaims(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)));
    }

    static String expired(UUID userId) {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.HOURS)))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    /** A real token whose payload is swapped to claim {@code victimId}, keeping the original signature. */
    static String withSwappedSubject(String realToken, UUID victimId) {
        String[] parts = realToken.split("\\.");
        String payload = "{\"sub\":\"" + victimId + "\",\"iss\":\"" + ISSUER + "\",\"exp\":"
                + (Instant.now().getEpochSecond() + 3600) + "}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + encoded + "." + parts[2];
    }

    static UUID subjectOf(String token) {
        return UUID.fromString(Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload().getSubject());
    }
}
