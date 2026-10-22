package dev.alexreyes.securevault.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.alexreyes.securevault.exception.ApiError;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP rate limit on the unauthenticated auth endpoints (login and
 * register share one bucket per IP). Exceeding it returns 429 with a
 * {@code Retry-After} header.
 *
 * <p>The client is identified by {@code request.getRemoteAddr()} only.
 * {@code X-Forwarded-For} is deliberately ignored: there is no trusted
 * reverse proxy in front of this app, so the header is client-controlled
 * and trusting it would make the limit trivially bypassable.
 *
 * <p>Buckets live in a Caffeine cache that is bounded in size and evicts
 * an IP's bucket after one refill period without requests - by then the
 * bucket would be full again anyway, so eviction never loosens the limit.
 *
 * <p>Known limitation: state is in-memory, so it resets on restart and is
 * not shared across instances (a Redis-backed Bucket4j store is the answer
 * for horizontal scaling).
 *
 * <p>Not a {@code @Component} for the same reason as
 * {@link JwtAuthenticationFilter}; {@link SecurityConfig} creates it.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> RATE_LIMITED_PATHS = Set.of("/api/auth/login", "/api/auth/register");
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_IPS = 100_000;

    private final long requestsPerMinute;
    private final JsonMapper jsonMapper;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_IPS)
            .expireAfterAccess(REFILL_PERIOD)
            .build();

    public AuthRateLimitFilter(long requestsPerMinute, JsonMapper jsonMapper) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be at least 1");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !RATE_LIMITED_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                "Too many requests, try again later", request.getRequestURI());
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillGreedy(requestsPerMinute, REFILL_PERIOD))
                .build();
    }
}
