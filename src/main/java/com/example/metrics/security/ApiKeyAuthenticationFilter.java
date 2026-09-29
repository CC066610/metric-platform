package com.example.metrics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates metric ingestion with a shared API key.
 *
 * <p>Ingestion gets its own credential rather than the dashboard login because
 * the callers are not alike. An agent is a program on a host you control: it can
 * keep a long-lived secret in a file the operator owns, and it has no browser to
 * prompt for a password. Reusing the dashboard account would also mean an agent
 * holds the authority to delete alert rules.
 *
 * <p>The filter authenticates and nothing else. It never rejects a request
 * itself: it either attaches an {@code ROLE_AGENT} authentication or leaves the
 * context empty, and {@link SecurityConfig} decides what that buys. Keeping the
 * decision in one place is what stops an endpoint from being protected by the
 * accident of which filter ran first.
 *
 * <p>When no key is configured, ingestion is accepted from loopback requests
 * only. A local agent then works with no setup, while a remote host cannot
 * inject metrics: it cannot make its packets appear to originate from
 * {@code 127.0.0.1}.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  /** Header an agent presents its key in. */
  public static final String HEADER = "X-API-Key";

  private static final String INGEST_PATH = "/api/metrics/batch";

  private static final String AGENT_PRINCIPAL = "agent";

  private final String configuredKey;

  /**
   * @param configuredKey expected key, or blank to accept loopback requests only
   */
  public ApiKeyAuthenticationFilter(String configuredKey) {
    this.configuredKey = configuredKey;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isIngestion(request) && isAuthentic(request)) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  AGENT_PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
    }
    chain.doFilter(request, response);
  }

  private static boolean isIngestion(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return "POST".equals(request.getMethod()) && INGEST_PATH.equals(path);
  }

  private boolean isAuthentic(HttpServletRequest request) {
    if (configuredKey == null || configuredKey.isBlank()) {
      return isLoopback(request.getRemoteAddr());
    }
    String presented = request.getHeader(HEADER);
    return presented != null && constantTimeEquals(configuredKey, presented);
  }

  /**
   * Compares without leaking the answer through timing.
   *
   * <p>An ordinary {@code String.equals} returns as soon as it finds a
   * difference, so the time it takes to reject a guess reveals how many leading
   * characters were right. That turns guessing a key from an exponential search
   * into a linear one.
   *
   * @param expected configured key
   * @param presented key from the request
   * @return whether they are equal
   */
  private static boolean constantTimeEquals(String expected, String presented) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean isLoopback(String remoteAddress) {
    try {
      return InetAddress.getByName(remoteAddress).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
