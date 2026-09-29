package com.example.metrics.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Throttles failed authentication attempts from one client address.
 *
 * <p>The operator password is the only thing between the network and the data,
 * and nothing else in the request path limits how fast it can be guessed. This
 * counts failures over a sliding window and refuses further attempts from that
 * address until enough of them have aged out.
 *
 * <p>A refusal is immediate rather than a delay: sleeping the request thread to
 * slow an attacker down hands them a way to exhaust the thread pool, so a
 * measured response has to cost no resources.
 *
 * <p>Clients are keyed by address, not by account name. Keying by account would
 * let anyone lock the operator out by sending the right username with the wrong
 * password, which turns the limiter itself into the denial of service.
 *
 * <p>State is per process and in memory, so a restart clears every counter and
 * two instances behind a load balancer would each allow the full quota. That is
 * recorded in the README rather than papered over.
 */
public final class AuthFailureLimiter {

  private static final String UNKNOWN_CLIENT = "unknown";

  private final int maxFailures;
  private final Duration window;
  private final Map<String, Deque<Instant>> failures;

  /**
   * Creates a limiter.
   *
   * @param maxFailures failures within the window before a client is refused
   * @param window how far back a failure still counts
   * @param maxTrackedClients ceiling on the number of addresses held in memory
   */
  public AuthFailureLimiter(int maxFailures, Duration window, int maxTrackedClients) {
    if (maxFailures < 1) {
      throw new IllegalArgumentException("maxFailures must be at least 1, was " + maxFailures);
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window must be positive, was " + window);
    }
    if (maxTrackedClients < 1) {
      throw new IllegalArgumentException(
          "maxTrackedClients must be at least 1, was " + maxTrackedClients);
    }
    this.maxFailures = maxFailures;
    this.window = window;
    this.failures = new BoundedCache<>(maxTrackedClients);
  }

  /**
   * Reports whether a client is refused right now, without recording anything.
   *
   * @param client client address; {@code null} or blank is treated as one shared client
   * @param now current time
   * @return the current decision
   */
  public synchronized Decision check(String client, Instant now) {
    return decide(client, now);
  }

  /**
   * Records a failed authentication.
   *
   * <p>Only the newest {@code maxFailures} attempts are kept. In-window attempts
   * are the newest ones, so discarding older records cannot change the verdict:
   * the count is {@code min(in-window attempts, maxFailures)} either way, and the
   * oldest record that survives is exactly the one whose expiry ends the refusal.
   * Without the cap, one address could grow its own list for as long as it liked.
   *
   * @param client client address; {@code null} or blank is treated as one shared client
   * @param now when the failure happened
   * @return the decision that applies from here on
   */
  public synchronized Decision recordFailure(String client, Instant now) {
    Deque<Instant> attempts = failures.computeIfAbsent(keyOf(client), ignored -> new ArrayDeque<>());
    attempts.addLast(now);
    while (attempts.size() > maxFailures) {
      attempts.removeFirst();
    }
    return decide(client, now);
  }

  /**
   * Clears a client's record after a successful authentication, so one mistyped
   * password does not count towards a lockout later in the same window.
   *
   * @param client client address; {@code null} or blank is treated as one shared client
   */
  public synchronized void recordSuccess(String client) {
    failures.remove(keyOf(client));
  }

  /** Visible for tests: how many failures are on record for a client. */
  synchronized int recordedFailures(String client) {
    Deque<Instant> attempts = failures.get(keyOf(client));
    return attempts == null ? 0 : attempts.size();
  }

  private Decision decide(String client, Instant now) {
    String key = keyOf(client);
    Deque<Instant> attempts = failures.get(key);
    if (attempts == null) {
      return Decision.allowed();
    }

    Instant oldestCounting = now.minus(window);
    while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(oldestCounting)) {
      attempts.removeFirst();
    }
    if (attempts.isEmpty()) {
      failures.remove(key);
      return Decision.allowed();
    }
    if (attempts.size() < maxFailures) {
      return Decision.allowed();
    }

    Duration retryAfter = window.minus(Duration.between(attempts.peekFirst(), now));
    return new Decision(true, retryAfter.isNegative() ? Duration.ZERO : retryAfter);
  }

  private static String keyOf(String client) {
    return client == null || client.isBlank() ? UNKNOWN_CLIENT : client;
  }

  /**
   * Whether a client is refused, and for how much longer.
   *
   * @param blocked true when further attempts are refused
   * @param retryAfter how long the client has to wait; zero when not blocked
   */
  public record Decision(boolean blocked, Duration retryAfter) {

    static Decision allowed() {
      return new Decision(false, Duration.ZERO);
    }
  }

  /**
   * A least-recently-used map with a hard ceiling, so a flood from many addresses
   * cannot grow it without bound. The ceiling is far above the number of distinct
   * clients a single process sees, so it only ever engages under attack.
   */
  private static final class BoundedCache<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 1L;

    private final int capacity;

    BoundedCache(int capacity) {
      super(16, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      return size() > capacity;
    }
  }
}
