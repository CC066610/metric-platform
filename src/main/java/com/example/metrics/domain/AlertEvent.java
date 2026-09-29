package com.example.metrics.domain;

import java.time.Instant;

/**
 * A durable alert history entry: either a firing or a resolution.
 *
 * @param ruleId owning rule
 * @param metricName metric the rule watches
 * @param kind transition that produced the record
 * @param observed value that caused the transition
 * @param threshold the bound in effect at that moment
 * @param message human-readable summary for notifications and the dashboard
 * @param at when the transition was evaluated
 */
public record AlertEvent(
    long ruleId,
    String metricName,
    Kind kind,
    double observed,
    double threshold,
    String message,
    Instant at) {

  /** Which transition produced an event. */
  public enum Kind {

    /** The rule crossed into {@link AlertStatus#FIRING}. */
    FIRING,

    /** The rule returned to {@link AlertStatus#OK} from firing. */
    RESOLVED
  }
}
