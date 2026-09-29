package com.example.metrics.domain;

/** Comparison applied by an alert rule. */
public enum Comparison {

  /** Fires when the observed value is greater than the bound. */
  GT,

  /** Fires when the observed value is less than the bound. */
  LT;

  /**
   * Parse an operator stored in the database.
   *
   * @param raw stored operator text
   * @return the matching comparison
   * @throws IllegalArgumentException when the operator is not recognised
   */
  public static Comparison from(String raw) {
    return switch (raw == null ? "" : raw.trim().toLowerCase()) {
      case "gt" -> GT;
      case "lt" -> LT;
      default -> throw new IllegalArgumentException("unsupported operator: " + raw);
    };
  }

  /**
   * Test one observed value against one bound.
   *
   * @param observed the measured value
   * @param bound the threshold or mean-plus-sigma bound
   * @return true when the rule considers the value breaching
   */
  public boolean breachedBy(double observed, double bound) {
    return this == GT ? observed > bound : observed < bound;
  }
}
