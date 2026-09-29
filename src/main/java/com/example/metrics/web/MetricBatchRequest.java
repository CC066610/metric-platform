package com.example.metrics.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Batch ingestion request.
 *
 * @param points points to store; the cap bounds request body size and insert batch size
 */
public record MetricBatchRequest(
    @NotEmpty @Size(max = 1000) List<@Valid MetricPointDto> points) {
}
