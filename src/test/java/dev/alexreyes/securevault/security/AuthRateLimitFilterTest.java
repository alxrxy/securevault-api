package dev.alexreyes.securevault.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the bucket cache itself: stale IPs must be evicted and
 * the number of tracked IPs must stay bounded, or the limiter is a slow
 * memory leak.
 */
class AuthRateLimitFilterTest {

    private final AtomicLong nanos = new AtomicLong();

    @Test
    void idleIpBucketIsEvictedAfterOneRefillPeriod() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(5, JsonMapper.builder().build(), nanos::get, 1_000);

        send(filter, "10.0.0.1");
        send(filter, "10.0.0.2");
        assertThat(filter.trackedIpCount()).isEqualTo(2);

        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        send(filter, "10.0.0.2"); // keeps 10.0.0.2 alive
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());

        assertThat(filter.trackedIpCount()).isEqualTo(1);
    }

    @Test
    void numberOfTrackedIpsIsBounded() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(5, JsonMapper.builder().build(), nanos::get, 10);

        for (int i = 0; i < 500; i++) {
            send(filter, "10.0." + (i / 256) + "." + (i % 256));
        }

        assertThat(filter.trackedIpCount()).isLessThanOrEqualTo(10);
    }

    @Test
    void bucketIsKeyedOnRemoteAddrNotForwardedFor() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, JsonMapper.builder().build(), nanos::get, 1_000);

        assertThat(send(filter, "10.0.0.9", "1.1.1.1")).isEqualTo(200);
        assertThat(send(filter, "10.0.0.9", "2.2.2.2")).isEqualTo(429);
        assertThat(filter.trackedIpCount()).isEqualTo(1);
    }

    @Test
    void otherPathsAreNotLimitedOrTracked() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, JsonMapper.builder().build(), nanos::get, 1_000);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.setRemoteAddr("10.0.0.3");

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(filter.trackedIpCount()).isZero();
    }

    private static int send(AuthRateLimitFilter filter, String remoteAddr) throws Exception {
        return send(filter, remoteAddr, null);
    }

    private static int send(AuthRateLimitFilter filter, String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
