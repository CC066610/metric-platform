package com.example.metrics.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

/**
 * One metric sample in a batch.
 *
 * @param name metric name, for example {@code cpu.usage}
 * @param ts sample time; must carry an offset or the server timezone decides
 * @param value sample value
 * @param tags flat label map, may be null
 */
public record MetricPointDto(
    @NotBlank String name,
    @NotNull Instant ts,
    @NotNull Double value,
    Map<String, String> tags) {
}
