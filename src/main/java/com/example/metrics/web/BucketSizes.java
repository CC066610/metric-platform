package com.example.metrics.web;

import java.time.Duration;

/**
 * Chooses an aggregation bucket width from the requested time span.
 *
 * <p>A fixed bucket width cannot serve both a one-hour and a thirty-day view:
 * the narrow query returns too many buckets to draw, and the wide one returns
 * too few. Selecting the width from the span keeps the response size roughly
 * constant, which is what makes the seven-day query fast.
 */
public final class BucketSizes {

  /** Longest span still served at one-minute resolution. */
  private static final Duration MINUTE_TIER = Duration.ofHours(6);

  /** Longest span still served at five-minute resolution. */
  private static final Duration FIVE_MINUTE_TIER = Duration.ofDays(7);

  private BucketSizes() {
  }

  /**
   * Pick a bucket width for a span.
   *
   * @param span requested duration, must be positive
   * @return bucket width in seconds: 60, 300, or 3600
   */
  public static int forSpan(Duration span) {
    if (span.compareTo(MINUTE_TIER) <= 0) {
      return 60;
    }
    if (span.compareTo(FIVE_MINUTE_TIER) <= 0) {
      return 300;
    }
    return 3600;
  }
}
