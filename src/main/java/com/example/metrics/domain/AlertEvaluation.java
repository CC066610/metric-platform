package com.example.metrics.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Result of evaluating one rule once: the state to persist plus the event to
 * record, if the evaluation moved the rule across a notification boundary.
 *
 * @param state state to persist, replacing whatever was stored
 * @param event transition to append to history, empty when nothing notable happened
 */
public record AlertEvaluation(AlertState state, Optional<AlertEvent> event) {

  /**
   * Build an evaluation that persists state without recording history.
   *
   * @param state state to persist
   * @return an evaluation with no event
   */
  public static AlertEvaluation silent(AlertState state) {
    return new AlertEvaluation(state, Optional.empty());
  }

  /**
   * Build an evaluation that also records a firing event.
   *
   * @param rule rule that fired
   * @param state next state
   * @param observed breaching value
   * @param bound bound that was crossed
   * @param at evaluation time
   * @return an evaluation carrying a firing event
   */
  public static AlertEvaluation firing(
      AlertRule rule, AlertState state, double observed, double bound, Instant at) {
    return new AlertEvaluation(state, Optional.of(new AlertEvent(
        rule.id(),
        rule.metricName(),
        AlertEvent.Kind.FIRING,
        observed,
        bound,
        "%s is %s %.3f (bound %.3f)".formatted(rule.metricName(), rule.comparison(), observed, bound),
        at)));
  }

  /**
   * Build an evaluation that also records a resolution event.
   *
   * @param rule rule that recovered
   * @param state next state
   * @param observed recovered value
   * @param bound bound that is no longer crossed
   * @param at evaluation time
   * @return an evaluation carrying a resolution event
   */
  public static AlertEvaluation resolved(
      AlertRule rule, AlertState state, double observed, double bound, Instant at) {
    return new AlertEvaluation(state, Optional.of(new AlertEvent(
        rule.id(),
        rule.metricName(),
        AlertEvent.Kind.RESOLVED,
        observed,
        bound,
        "%s recovered at %.3f (bound %.3f)".formatted(rule.metricName(), observed, bound),
        at)));
  }
}
