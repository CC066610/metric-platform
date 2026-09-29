package com.example.metrics.web;

import com.example.metrics.service.MetricIngestionService;
import com.example.metrics.store.MetricStore;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Ingestion and query endpoints for metric points. */
@RestController
@RequestMapping("/api/metrics")
public class MetricController {

  private final MetricStore store;
  private final MetricIngestionService ingestion;

  /**
   * Create the controller.
   *
   * @param store metric persistence, used by the read endpoints
   * @param ingestion write path selection for the ingestion endpoint
   */
  public MetricController(MetricStore store, MetricIngestionService ingestion) {
    this.store = store;
    this.ingestion = ingestion;
  }

  /**
   * Store a batch of metric points.
   *
   * <p>The response reports which write path served the request, so the
   * selection is visible in production rather than inferred from configuration.
   *
   * @param request validated batch, at most one thousand points
   * @return the number of accepted points and the route taken
   */
  @PostMapping("/batch")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> ingest(@Valid @RequestBody MetricBatchRequest request) {
    List<MetricStore.MetricPoint> points = request.points().stream()
        .map(point -> new MetricStore.MetricPoint(point.name(), point.ts(), point.value(), point.tags()))
        .toList();
    MetricIngestionService.IngestResult result = ingestion.ingest(points);
    return Map.of(
        "accepted", result.written(),
        "route", result.route().name().toLowerCase());
  }

  /**
   * Ingestion summary for the dashboard: the last write path taken and the
   * configured crossover threshold.
   *
   * @return last route observation, or a null route when nothing has been ingested
   */
  @GetMapping("/ingestion")
  public Map<String, Object> ingestionStatus() {
    Map<String, Object> status = new java.util.LinkedHashMap<>();
    status.put("copyMinBatchSize", ingestion.copyMinBatchSize());
    status.put("forcedPath", ingestion.forcedPath());
    ingestion.lastRoute().ifPresentOrElse(
        record -> {
          status.put("route", record.route().name().toLowerCase());
          status.put("rows", record.rows());
          status.put("at", record.at());
        },
        () -> status.put("route", null));
    return status;
  }

  /**
   * Aggregate one metric over a time range.
   *
   * @param name metric to aggregate
   * @param from inclusive range start, ISO-8601 with offset
   * @param to exclusive range end, ISO-8601 with offset
   * @param bucketSeconds explicit bucket width; when absent the width is chosen from the span
   * @return aggregated buckets ordered by time
   */
  @GetMapping("/query")
  public List<SeriesPointDto> query(
      @RequestParam String name,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) Integer bucketSeconds) {

    if (!to.isAfter(from)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must be after 'from'");
    }
    int width = bucketSeconds == null
        ? BucketSizes.forSpan(Duration.between(from, to))
        : Math.max(1, Math.min(bucketSeconds, 86_400));

    return store.queryBucketed(name, from, to, width).stream()
        .map(point -> new SeriesPointDto(
            point.bucket(), point.avg(), point.max(), point.min(), point.count()))
        .toList();
  }

  /**
   * List stored metrics with point counts.
   *
   * @return one entry per metric name
   */
  @GetMapping("/names")
  public List<Map<String, Object>> names() {
    return store.listMetrics();
  }

  /**
   * Total stored point count, used by the smoke test.
   *
   * @return row count of {@code metric_point}
   */
  @GetMapping("/count")
  public Map<String, Long> count() {
    return Map.of("points", store.count());
  }
}
