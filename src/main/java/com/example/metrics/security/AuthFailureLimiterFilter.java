package com.example.metrics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses requests that present a credential from a client address that has
 * failed authentication too often.
 *
 * <p>Only requests that carry a credential are counted. A request without one
 * cannot be guessing anything, and counting it would also throttle the endpoints
 * that exist to be reachable without credentials — the load balancer's health
 * probe first of all, which is how a working instance gets taken out of rotation.
 *
 * <p>A refused client is rejected before authentication runs, so a lockout costs
 * no password hashing, and the refusal is a status and a header rather than a
 * log line, so nothing here can be used to fill a disk.
 */
final class AuthFailureLimiterFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(AuthFailureLimiterFilter.class);

  private static final String TOO_MANY_FAILURES =
      "{\"error\":\"too many failed authentication attempts\"}";

  private final AuthFailureLimiter limiter;

  AuthFailureLimiterFilter(AuthFailureLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (!carriesCredential(request)) {
      chain.doFilter(request, response);
      return;
    }

    String client = request.getRemoteAddr();
    Instant now = Instant.now();

    AuthFailureLimiter.Decision decision = limiter.check(client, now);
    if (decision.blocked()) {
      refuse(response, decision);
      return;
    }

    chain.doFilter(request, response);

    int status = response.getStatus();
    if (status == HttpServletResponse.SC_UNAUTHORIZED) {
      AuthFailureLimiter.Decision after = limiter.recordFailure(client, now);
      if (after.blocked()) {
        // Logged on the failure that trips the limit, not on every refused
        // request afterwards: a client in a retry loop must not be able to
        // write an unbounded number of lines.
        log.warn(
            "client {} has {} failed authentications; refusing further attempts for {}s",
            client,
            limiter.recordedFailures(client),
            after.retryAfter().toSeconds());
      }
    } else if (status < HttpStatus.BAD_REQUEST.value()) {
      // A request that carried a working credential clears the record.
      limiter.recordSuccess(client);
    }
  }

  private static boolean carriesCredential(HttpServletRequest request) {
    return request.getHeader(HttpHeaders.AUTHORIZATION) != null
        || request.getHeader(ApiKeyAuthenticationFilter.HEADER) != null;
  }

  private static void refuse(HttpServletResponse response, AuthFailureLimiter.Decision decision)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    // Without this a client cannot tell a temporary lockout from a permanent
    // refusal, and has no reason to come back later.
    response.setHeader(
        HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, decision.retryAfter().toSeconds())));
    response.setContentType("application/json");
    response.getWriter().write(TOO_MANY_FAILURES);
  }
}
