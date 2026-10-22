package dev.alexreyes.securevault.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP rate limit on the unauthenticated auth endpoints (login and
 * register share one bucket per IP). Exceeding it returns 429 with a
 * {@code Retry-After} header.
 *
 * <p>The client is identified by {@code request.getRemoteAddr()} only.
 * {@code X-Forwarded-For} is deliberately ignored: there is no trusted
 * reverse proxy in front of this app, so the header is client-controlled
 * and trusting it would make the limit trivially bypassable. IPv4 clients
 * are keyed by their full address; IPv6 clients by their /64 prefix, since
 * a single host or subscriber is normally assigned a whole /64 and could
 * otherwise rotate through 2^64 addresses (see {@link #clientKey}).
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

    /**
     * Matched the same way Spring Security and MVC match (on the decoded,
     * normalized path). Comparing the raw request URI instead let
     * {@code /api/auth/%6Cogin} reach the login handler unthrottled.
     */
    private static final RequestMatcher RATE_LIMITED = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/api/auth/login"),
            PathPatternRequestMatcher.pathPattern("/api/auth/register"));
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_IPS = 100_000;
    private static final int IPV6_PREFIX_BYTES = 8; // /64

    private final long requestsPerMinute;
    private final JsonMapper jsonMapper;
    private final Cache<String, Bucket> buckets;

    public AuthRateLimitFilter(long requestsPerMinute, JsonMapper jsonMapper) {
        this(requestsPerMinute, jsonMapper, Ticker.systemTicker(), MAX_TRACKED_IPS);
    }

    /** Visible for tests: a controllable clock and size bound for the bucket cache. */
    AuthRateLimitFilter(long requestsPerMinute, JsonMapper jsonMapper, Ticker ticker, long maxTrackedIps) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be at least 1");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.jsonMapper = jsonMapper;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxTrackedIps)
                .expireAfterAccess(REFILL_PERIOD)
                .ticker(ticker)
                // Run eviction on the calling thread instead of the common
                // ForkJoinPool; it's cheap and keeps eviction deterministic.
                .executor(Runnable::run)
                .build();
    }

    /** Visible for tests: number of IPs currently holding a bucket. */
    long trackedIpCount() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RATE_LIMITED.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        Bucket bucket = buckets.get(clientKey(request.getRemoteAddr()), key -> newBucket());
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

    /**
     * IPv4 → the address itself. IPv6 → its /64 network, e.g.
     * {@code 2001:db8:1:2:aaaa::1} → {@code 2001:db8:1:2:0:0:0:0/64}, so all
     * spellings and all hosts of one /64 share a bucket. IPv4-mapped IPv6
     * ({@code ::ffff:10.0.0.1}) is treated as the IPv4 address.
     */
    static String clientKey(String remoteAddr) {
        // Only parse IPv6 literals: a ':' can't appear in an IPv4 address or
        // hostname, so getByName below never triggers a DNS lookup.
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) {
            return remoteAddr;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (!(address instanceof Inet6Address)) {
                return address.getHostAddress();
            }
            byte[] network = Arrays.copyOf(address.getAddress(), 16);
            Arrays.fill(network, IPV6_PREFIX_BYTES, network.length, (byte) 0);
            return InetAddress.getByAddress(network).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            return remoteAddr;
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillGreedy(requestsPerMinute, REFILL_PERIOD))
                .build();
    }
}
