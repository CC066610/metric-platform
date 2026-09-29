package com.example.metrics.domain;

/** Lifecycle position of one alert rule. */
public enum AlertStatus {

  /** The metric is within bounds. */
  OK,

  /** The metric is breaching, but fewer than {@code minConsecutive} times in a row. */
  PENDING,

  /** The metric is breaching and a notification has been delivered. */
  FIRING;

  /**
   * Parse a status stored in the database.
   *
   * @param raw stored text, may be null for a rule that has never been evaluated
   * @return the matching status, or {@link #OK} when the value is absent or unknown
   */
  public static AlertStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      return OK;
    }
    try {
      return valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException unknown) {
      return OK;
    }
  }
}
