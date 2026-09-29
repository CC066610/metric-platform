package com.example.metrics.domain;

/**
 * One alert rule as stored in {@code alert_rule}.
 *
 * @param id rule identity
 * @param metricName metric the rule watches
 * @param comparison bound direction
 * @param threshold absolute bound, used when {@code sigmaMultiplier} is absent or not positive
 * @param sigmaMultiplier when positive, the bound is mean + multiplier * stddev over {@code windowSeconds}
 * @param windowSeconds lookback used to compute the sigma baseline
 * @param minConsecutive consecutive breaching evaluations required before firing
 * @param cooldownSeconds quiet period after a firing notification
 * @param enabled whether the rule participates in evaluation
 */
public record AlertRule(
    long id,
    String metricName,
    Comparison comparison,
    double threshold,
    Double sigmaMultiplier,
    int windowSeconds,
    int minConsecutive,
    int cooldownSeconds,
    boolean enabled) {

  /**
   * Whether this rule derives its bound from statistics instead of a fixed number.
   *
   * @return true when a positive sigma multiplier is configured
   */
  public boolean usesSigmaBound() {
    return sigmaMultiplier != null && sigmaMultiplier > 0;
  }
}
