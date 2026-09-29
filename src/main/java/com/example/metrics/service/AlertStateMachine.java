package com.example.metrics.service;

import com.example.metrics.domain.AlertEvaluation;
import com.example.metrics.domain.AlertRule;
import com.example.metrics.domain.AlertState;
import com.example.metrics.domain.AlertStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Pure decision logic for one alert rule: given the stored state, an observed
 * value, and the effective bound, produce the next state and any event to
 * record.
 *
 * <p>The two mechanisms that keep alerts quiet are deliberately separate:
 * <ul>
 *   <li>{@code minConsecutive} filters transient spikes before anything is sent.</li>
 *   <li>{@code cooldownSeconds} suppresses repeats while the metric stays breaching.</li>
 * </ul>
 * A metric that keeps breaching stays in {@link AlertStatus#FIRING}; the
 * cooldown only controls whether another notification is emitted, which is why
 * the duration of an incident stays visible in the stored state.
 *
 * <p>This class has no framework or database dependency so it can be tested as
 * a decision table.
 */
@Component
public class AlertStateMachine {

  /**
   * Evaluate one observation against one rule.
   *
   * @param rule rule being evaluated
   * @param state state loaded before this evaluation
   * @param bound bound to compare against, already resolved from threshold or baseline
   * @param observed value observed at {@code now}
   * @param now evaluation time
   * @return next state plus the event to append, if the rule crossed a notification boundary
   */
  public AlertEvaluation evaluate(
      AlertRule rule, AlertState state, Bound bound, double observed, Instant now) {

    boolean breaching = rule.comparison().breachedBy(observed, bound.value());

    if (!breaching) {
      if (state.status() == AlertStatus.FIRING) {
        AlertState recovered = new AlertState(
            rule.id(), AlertStatus.OK, 0, state.lastFiredAt(), observed);
        return AlertEvaluation.resolved(rule, recovered, observed, bound.value(), now);
      }
      return AlertEvaluation.silent(
          new AlertState(rule.id(), AlertStatus.OK, 0, state.lastFiredAt(), observed));
    }

    int consecutive = state.consecutive() + 1;

    if (consecutive < rule.minConsecutive()) {
      // Breaching, but not for long enough to be worth anyone's attention.
      return AlertEvaluation.silent(
          new AlertState(rule.id(), AlertStatus.PENDING, consecutive, state.lastFiredAt(), observed));
    }

    boolean cooling = withinCooldown(state.lastFiredAt(), rule.cooldownSeconds(), now);
    AlertState firing = new AlertState(
        rule.id(), AlertStatus.FIRING, consecutive, state.lastFiredAt(), observed);

    if (state.status() != AlertStatus.FIRING) {
      // First crossing into firing always notifies, even if a cooldown from an
      // earlier incident has not fully elapsed; otherwise a recovery followed by
      // an immediate new incident would be silent.
      AlertState notified = new AlertState(
          rule.id(), AlertStatus.FIRING, consecutive, now, observed);
      return AlertEvaluation.firing(rule, notified, observed, bound.value(), now);
    }

    if (cooling) {
      // Still the same incident: keep the original lastFiredAt so the cooldown
      // does not slide forward on every evaluation.
      return AlertEvaluation.silent(firing);
    }

    AlertState reNotified = new AlertState(
        rule.id(), AlertStatus.FIRING, consecutive, now, observed);
    return AlertEvaluation.firing(rule, reNotified, observed, bound.value(), now);
  }

  /**
   * Whether a firing notification happened recently enough to suppress another.
   *
   * @param lastFiredAt time of the previous notification, or null when none was ever sent
   * @param cooldownSeconds configured quiet period
   * @param now evaluation time
   * @return true when another notification must be suppressed
   */
  public boolean withinCooldown(Instant lastFiredAt, int cooldownSeconds, Instant now) {
    if (lastFiredAt == null) {
      return false;
    }
    return Duration.between(lastFiredAt, now).getSeconds() < cooldownSeconds;
  }

  /**
   * The bound a rule is currently comparing against.
   *
   * @param value numeric bound
   * @param statistical whether the bound came from a mean-plus-sigma baseline
   */
  public record Bound(double value, boolean statistical) {

    /**
     * Build an absolute bound.
     *
     * @param threshold configured threshold
     * @return the bound
     */
    public static Bound absolute(double threshold) {
      return new Bound(threshold, false);
    }

    /**
     * Build a statistical bound.
     *
     * @param value computed mean plus multiplier times standard deviation
     * @return the bound
     */
    public static Bound statistical(double value) {
      return new Bound(value, true);
    }
  }
}
