package dev.alexreyes.securevault.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates stateless JWTs.
 *
 * <p>Security properties, explicitly:
 * <ul>
 *   <li><b>Signature:</b> HMAC-SHA256 via a secret key decoded from
 *       {@code app.jwt.secret} (must be base64, >= 32 bytes decoded -
 *       generate with {@code openssl rand -base64 48}).</li>
 *   <li><b>Algorithm confusion:</b> {@code verifyWith(SecretKey)} restricts
 *       the parser to HMAC algorithms compatible with this key - an
 *       attacker-supplied token claiming "alg: none" or an asymmetric
 *       algorithm is rejected before the signature is even checked.</li>
 *   <li><b>Expiration:</b> enforced by the parser automatically from the
 *       {@code exp} claim; a small clock-skew allowance (30s) avoids
 *       false rejections from minor clock drift between processes.</li>
 *   <li><b>Issuer:</b> required to equal {@link #ISSUER}, rejecting tokens
 *       that happen to verify against this key but weren't meant for this
 *       service.</li>
 * </ul>
 */
@Component
public class JwtService {

    private static final String ISSUER = "securevault-api";

    private final SecretKey key;
    private final Duration tokenTtl;

    public JwtService(
            @Value("${app.jwt.secret}") String base64Secret,
            @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(base64Secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.jwt.secret must be valid base64. Generate one with: openssl rand -base64 48", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must decode to at least 32 bytes (256 bits). "
                            + "Generate one with: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.tokenTtl = Duration.ofMinutes(expirationMinutes);
    }

    public String generateToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenTtl)))
                .signWith(key)
                .compact();
    }

    public long expirationSeconds() {
        return tokenTtl.toSeconds();
    }

    /**
     * Validates signature, issuer, and expiration, and returns the user id
     * from the subject claim.
     *
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has an
     *     invalid signature, is expired, or has the wrong issuer. Callers
     *     treat every failure mode identically - "not authenticated" - and
     *     never surface which specific check failed to the client.
     */
    public UUID validateAndGetUserId(String token) {
        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(key)
                .require(Claims.ISSUER, ISSUER)
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token);
        return UUID.fromString(parsed.getPayload().getSubject());
    }
}
