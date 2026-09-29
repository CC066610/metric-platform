package com.example.metrics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the login throttle.
 *
 * <p>The limiter is a pure state machine over an injected clock, so every window
 * boundary is exercised here at an exact instant rather than by sleeping. The
 * filter around it is verified against a running instance instead.
 */
class AuthFailureLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
  private static final String CLIENT = "203.0.113.7";

  private static AuthFailureLimiter limiter(int maxFailures) {
    return new AuthFailureLimiter(maxFailures, WINDOW, 10_000);
  }

  private static AuthFailureLimiter.Decision fail(
      AuthFailureLimiter limiter, String client, Instant at) {
    return limiter.recordFailure(client, at);
  }

  @Test
  @DisplayName("attempts below the limit are allowed and not reported as blocked")
  void belowTheLimitIsAllowed() {
    AuthFailureLimiter limiter = limiter(10);
    for (int attempt = 0; attempt < 9; attempt++) {
      assertThat(fail(limiter, CLIENT, T0.plusSeconds(attempt)).blocked()).isFalse();
    }
    assertThat(limiter.check(CLIENT, T0.plusSeconds(9)).blocked()).isFalse();
  }

  @Test
  @DisplayName("the failure that reaches the limit is itself refused, and so is the next check")
  void reachingTheLimitRefuses() {
    AuthFailureLimiter limiter = limiter(10);
    AuthFailureLimiter.Decision last = null;
    for (int attempt = 0; attempt < 10; attempt++) {
      last = fail(limiter, CLIENT, T0.plusSeconds(attempt));
    }
    assertThat(last).isNotNull();
    assertThat(last.blocked()).isTrue();
    assertThat(limiter.check(CLIENT, T0.plusSeconds(10)).blocked()).isTrue();
  }

  @Test
  @DisplayName("the refusal ends exactly when retryAfter says it will")
  void theRefusalEndsWhenRetryAfterSays() {
    AuthFailureLimiter limiter = limiter(3);
    for (int attempt = 0; attempt < 3; attempt++) {
      fail(limiter, CLIENT, T0.plusSeconds(attempt));
    }

    AuthFailureLimiter.Decision blocked = limiter.check(CLIENT, T0.plusSeconds(899));
    assertThat(blocked.blocked()).isTrue();
    assertThat(blocked.retryAfter()).isEqualTo(Duration.ofSeconds(1));
    assertThat(limiter.check(CLIENT, T0.plusSeconds(899).plus(blocked.retryAfter())).blocked())
        .isFalse();
  }

  @Test
  @DisplayName("retryAfter counts down to the moment the oldest counted failure leaves the window")
  void retryAfterCountsDown() {
    AuthFailureLimiter limiter = limiter(3);
    for (int attempt = 0; attempt < 3; attempt++) {
      fail(limiter, CLIENT, T0.plusSeconds(attempt));
    }

    assertThat(limiter.check(CLIENT, T0.plusSeconds(3)).retryAfter())
        .isEqualTo(WINDOW.minusSeconds(3));
    assertThat(limiter.check(CLIENT, T0.plusSeconds(303)).retryAfter())
        .isEqualTo(WINDOW.minusSeconds(303));
  }

  @Test
  @DisplayName("failures older than the window stop counting, so the window slides")
  void theWindowSlides() {
    AuthFailureLimiter limiter = limiter(3);
    fail(limiter, CLIENT, T0);
    fail(limiter, CLIENT, T0.plusSeconds(1));
    AuthFailureLimiter.Decision third = fail(limiter, CLIENT, T0.plus(WINDOW).plusSeconds(1));
    assertThat(third.blocked()).isFalse();
    assertThat(limiter.check(CLIENT, T0.plus(WINDOW).plusSeconds(2)).blocked()).isFalse();
  }

  @Test
  @DisplayName("a successful authentication clears the failures recorded before it")
  void successClearsTheRecord() {
    AuthFailureLimiter limiter = limiter(3);
    fail(limiter, CLIENT, T0);
    fail(limiter, CLIENT, T0.plusSeconds(1));
    limiter.recordSuccess(CLIENT);
    fail(limiter, CLIENT, T0.plusSeconds(2));
    fail(limiter, CLIENT, T0.plusSeconds(3));

    assertThat(limiter.check(CLIENT, T0.plusSeconds(4)).blocked()).isFalse();
    assertThat(fail(limiter, CLIENT, T0.plusSeconds(5)).blocked()).isTrue();
  }

  @Test
  @DisplayName("one client's failures do not throttle another client")
  void clientsAreIndependent() {
    AuthFailureLimiter limiter = limiter(3);
    for (int attempt = 0; attempt < 3; attempt++) {
      fail(limiter, "198.51.100.1", T0.plusSeconds(attempt));
    }
    assertThat(limiter.check("198.51.100.1", T0.plusSeconds(4)).blocked()).isTrue();
    assertThat(limiter.check("198.51.100.2", T0.plusSeconds(4)).blocked()).isFalse();
  }

  @Test
  @DisplayName("a missing client address is one shared client rather than an error")
  void missingAddressIsOneClient() {
    AuthFailureLimiter limiter = limiter(2);
    fail(limiter, null, T0);
    assertThat(fail(limiter, "   ", T0.plusSeconds(1)).blocked()).isTrue();
    assertThat(limiter.check(null, T0.plusSeconds(2)).blocked()).isTrue();
  }

  @Test
  @DisplayName("a client that keeps failing cannot grow its own record without bound")
  void perClientMemoryIsBounded() {
    AuthFailureLimiter limiter = limiter(10);
    for (int attempt = 0; attempt < 10_000; attempt++) {
      fail(limiter, CLIENT, T0.plusMillis(attempt));
    }
    assertThat(limiter.recordedFailures(CLIENT)).isEqualTo(10);
  }

  @Test
  @DisplayName("a flood of distinct addresses evicts the least recently seen")
  void trackedClientsAreBounded() {
    AuthFailureLimiter limiter = new AuthFailureLimiter(10, WINDOW, 3);
    for (int index = 0; index < 10; index++) {
      fail(limiter, "client-" + index, T0.plusSeconds(index));
    }
    assertThat(limiter.recordedFailures("client-0")).isZero();
    assertThat(limiter.recordedFailures("client-9")).isEqualTo(1);
  }

  @Test
  @DisplayName("a limit of zero, an empty window, or no client budget is rejected at construction")
  void nonsensicalConfigurationIsRejected() {
    assertThatThrownBy(() -> new AuthFailureLimiter(0, WINDOW, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthFailureLimiter(-1, WINDOW, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthFailureLimiter(10, Duration.ZERO, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthFailureLimiter(10, Duration.ofSeconds(-1), 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthFailureLimiter(10, null, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthFailureLimiter(10, WINDOW, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("concurrent failures are all counted without losing the per-client bound")
  void concurrentFailuresStayBounded() throws InterruptedException {
    AuthFailureLimiter limiter = limiter(10);
    int threads = 8;
    int perThread = 500;
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();

    for (int index = 0; index < threads; index++) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int attempt = 0; attempt < perThread; attempt++) {
                  limiter.recordFailure(CLIENT, T0);
                }
              });
      worker.start();
      workers.add(worker);
    }

    start.countDown();
    for (Thread worker : workers) {
      worker.join();
    }

    assertThat(limiter.recordedFailures(CLIENT)).isEqualTo(10);
    assertThat(limiter.check(CLIENT, T0).blocked()).isTrue();
  }

  @Test
  @DisplayName("recording a success for a client that never failed is harmless")
  void successForUnknownClientIsIgnored() {
    AuthFailureLimiter limiter = limiter(3);
    limiter.recordSuccess("never-seen");
    assertThat(limiter.check("never-seen", T0).blocked()).isFalse();
  }
}
