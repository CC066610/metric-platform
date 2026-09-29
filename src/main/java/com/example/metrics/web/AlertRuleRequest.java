package com.example.metrics.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Rule creation payload.
 *
 * @param metricName metric to watch
 * @param operator {@code gt} or {@code lt}
 * @param threshold absolute bound, ignored when {@code sigmaMultiplier} is positive
 * @param sigmaMultiplier statistical multiplier; null or non-positive selects the absolute bound
 * @param windowSeconds sigma baseline lookback
 * @param minConsecutive consecutive breaching evaluations required before firing
 * @param cooldownSeconds quiet period after a firing notification
 */
public record AlertRuleRequest(
    @NotBlank String metricName,
    @Pattern(regexp = "gt|lt", message = "operator must be gt or lt") String operator,
    double threshold,
    Double sigmaMultiplier,
    @Positive int windowSeconds,
    @PositiveOrZero int minConsecutive,
    @PositiveOrZero int cooldownSeconds) {
}
