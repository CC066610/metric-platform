package com.example.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.metrics.domain.AlertEvaluation;
import com.example.metrics.domain.AlertEvent;
import com.example.metrics.domain.AlertRule;
import com.example.metrics.domain.AlertState;
import com.example.metrics.domain.AlertStatus;
import com.example.metrics.domain.Comparison;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decision-table tests for the alert state machine. These cover the behaviour
 * the platform is judged on: a single spike must not notify, a sustained breach
 * must notify exactly once, and a recovery must notify again.
 */
class AlertStateMachineTest {

  private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

  private final AlertStateMachine machine = new AlertStateMachine();
  private final AlertStateMachine.Bound bound = AlertStateMachine.Bound.absolute(85);

  private static AlertRule rule(int minConsecutive, int cooldownSeconds) {
    // Argument order follows AlertRule: id, metricName, comparison, threshold,
    // sigmaMultiplier, windowSeconds, minConsecutive, cooldownSeconds, enabled.
    // The threshold is written as a double literal because passing an int here
    // makes the compiler treat the following null as a Double and reject it.
    return new AlertRule(1L, "cpu.usage", Comparison.GT, 85d, null, 300,
        minConsecutive, cooldownSeconds, true);
  }

  @Test
  @DisplayName("a single spike stays pending and notifies nobody")
  void singleSpikeDoesNotNotify() {
    AlertRule rule = rule(2, 300);

    AlertEvaluation first = machine.evaluate(rule, AlertState.initial(1L), bound, 90, T0);

    assertThat(first.state().status()).isEqualTo(AlertStatus.PENDING);
    assertThat(first.state().consecutive()).isEqualTo(1);
    assertThat(first.event()).isEmpty();
  }

  @Test
  @DisplayName("two consecutive breaches fire once and record one event")
  void sustainedBreachFires() {
    AlertRule rule = rule(2, 300);
    AlertState pending = new AlertState(1L, AlertStatus.PENDING, 1, null, 90d);

    AlertEvaluation firing = machine.evaluate(rule, pending, bound, 91, T0);

    assertThat(firing.state().status()).isEqualTo(AlertStatus.FIRING);
    assertThat(firing.state().lastFiredAt()).isEqualTo(T0);
    assertThat(firing.event()).isPresent();
    assertThat(firing.event().orElseThrow().kind()).isEqualTo(AlertEvent.Kind.FIRING);
  }

  @Test
  @DisplayName("a still-breaching metric inside the cooldown does not notify again")
  void cooldownSuppressesRepeatNotification() {
    AlertRule rule = rule(2, 300);
    AlertState firing = new AlertState(1L, AlertStatus.FIRING, 5, T0, 95d);

    AlertEvaluation again = machine.evaluate(rule, firing, bound, 96, T0.plusSeconds(60));

    assertThat(again.event()).isEmpty();
    assertThat(again.state().status()).isEqualTo(AlertStatus.FIRING);
    // The incident is ongoing; the cooldown must not slide forward on every pass.
    assertThat(again.state().lastFiredAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("a still-breaching metric past the cooldown re-notifies")
  void cooldownExpiryRenotifies() {
    AlertRule rule = rule(2, 300);
    AlertState firing = new AlertState(1L, AlertStatus.FIRING, 20, T0, 96d);

    AlertEvaluation again = machine.evaluate(rule, firing, bound, 97, T0.plusSeconds(301));

    assertThat(again.event()).isPresent();
    assertThat(again.state().lastFiredAt()).isEqualTo(T0.plusSeconds(301));
  }

  @Test
  @DisplayName("recovery from firing records a resolution event")
  void recoveryResolves() {
    AlertRule rule = rule(2, 300);
    AlertState firing = new AlertState(1L, AlertStatus.FIRING, 9, T0, 96d);

    AlertEvaluation resolved = machine.evaluate(rule, firing, bound, 40, T0.plusSeconds(600));

    assertThat(resolved.state().status()).isEqualTo(AlertStatus.OK);
    assertThat(resolved.state().consecutive()).isZero();
    assertThat(resolved.event()).isPresent();
    assertThat(resolved.event().orElseThrow().kind()).isEqualTo(AlertEvent.Kind.RESOLVED);
  }

  @Test
  @DisplayName("recovery from pending is silent because nothing was announced")
  void recoveryFromPendingIsSilent() {
    AlertRule rule = rule(2, 300);
    AlertState pending = new AlertState(1L, AlertStatus.PENDING, 1, null, 90d);

    AlertEvaluation resolved = machine.evaluate(rule, pending, bound, 40, T0);

    assertThat(resolved.event()).isEmpty();
    assertThat(resolved.state().status()).isEqualTo(AlertStatus.OK);
  }

  @Test
  @DisplayName("a new incident after recovery notifies even if the old cooldown has not elapsed")
  void newIncidentAfterRecoveryNotifies() {
    AlertRule rule = rule(1, 300);
    // Resolved 10 seconds ago, so the 300 second cooldown from the previous
    // incident is still open.
    AlertState ok = new AlertState(1L, AlertStatus.OK, 0, T0, 40d);

    AlertEvaluation firing = machine.evaluate(rule, ok, bound, 99, T0.plusSeconds(10));

    assertThat(firing.event()).isPresent();
  }

  @Test
  @DisplayName("a less-than rule fires below its bound")
  void lessThanRuleFiresBelowBound() {
    AlertRule rule = new AlertRule(2L, "disk.free", Comparison.LT, 10d, null, 300, 1, 300, true);
    AlertStateMachine.Bound low = AlertStateMachine.Bound.absolute(10);

    AlertEvaluation firing = machine.evaluate(rule, AlertState.initial(2L), low, 4, T0);

    assertThat(firing.event()).isPresent();
  }

  @Test
  @DisplayName("an absolute rule is never treated as statistical")
  void absoluteRuleIsNotStatistical() {
    assertThat(rule(2, 300).usesSigmaBound()).isFalse();
  }
}
