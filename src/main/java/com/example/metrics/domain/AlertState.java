package com.example.metrics.domain;

import java.time.Instant;

/**
 * Current machine state for one rule, as stored in {@code alert_state}.
 *
 * @param ruleId owning rule
 * @param status lifecycle position
 * @param consecutive breaching evaluations in a row
 * @param lastFiredAt when the last firing notification was delivered, or null
 * @param lastValue most recently observed value, or null before the first evaluation
 */
public record AlertState(
    long ruleId,
    AlertStatus status,
    int consecutive,
    Instant lastFiredAt,
    Double lastValue) {

  /**
   * State for a rule that has never been evaluated.
   *
   * @param ruleId owning rule
   * @return an {@link AlertStatus#OK} state with no history
   */
  public static AlertState initial(long ruleId) {
    return new AlertState(ruleId, AlertStatus.OK, 0, null, null);
  }
}
