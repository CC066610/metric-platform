package com.example.metrics.service;

import com.example.metrics.config.MetricPlatformProperties;
import com.example.metrics.store.CopyMetricStore;
import com.example.metrics.store.MetricStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Writes metric points through whichever ingestion path fits the batch.
 *
 * <p>The two paths are not interchangeable: a JDBC batch of {@code INSERT}
 * statements pays per-row parser and planner work, while {@code COPY} pays a
 * fixed per-call protocol setup but almost nothing per row. Below roughly a
 * hundred rows the fixed cost dominates and {@code COPY} is measurably slower,
 * so the choice is made per request rather than once for the deployment.
 *
 * <p>Which path served a request is reported back to the caller, so the
 * selection is observable rather than something a reader has to infer from
 * configuration.
 */
@Service
public class MetricIngestionService {

  private final MetricStore batchStore;
  private final CopyMetricStore copyStore;
  private final MetricPlatformProperties properties;

  /**
   * Last route taken, held in memory only.
   *
   * <p>This answers "which write path is serving traffic right now" for an
   * operator watching the dashboard. It is deliberately not persisted: it is an
   * observation about a running process and must not outlive it.
   */
  private final AtomicReference<RouteRecord> lastRoute = new AtomicReference<>();

  /**
   * Create the service.
   *
   * @param batchStore JDBC batch path
   * @param copyStore COPY path
   * @param properties deployment settings, including the crossover threshold
   */
  public MetricIngestionService(
      MetricStore batchStore, CopyMetricStore copyStore, MetricPlatformProperties properties) {
    this.batchStore = batchStore;
    this.copyStore = copyStore;
    this.properties = properties;
  }

  /**
   * Store a batch, selecting the write path from its size.
   *
   * @param points points to store, already validated and non-empty
   * @return the route taken and the number of rows written
   */
  public IngestResult ingest(List<MetricStore.MetricPoint> points) {
    Route route = selectRoute(points.size());
    int written = switch (route) {
      case COPY -> copyStore.copyIn(points);
      case BATCH -> batchStore.insertBatch(points);
    };
    RouteRecord record = new RouteRecord(route, points.size(), Instant.now());
    lastRoute.set(record);
    return new IngestResult(route, written);
  }

  /**
   * Most recent ingestion, for the dashboard.
   *
   * @return the last route taken, or empty when nothing has been ingested since startup
   */
  public java.util.Optional<RouteRecord> lastRoute() {
    return java.util.Optional.ofNullable(lastRoute.get());
  }

  /**
   * Configured crossover threshold, for display next to live traffic.
   *
   * @return rows per request at which COPY takes over
   */
  public int copyMinBatchSize() {
    return properties.ingestion().copyMinBatchSize();
  }

  /**
   * Configured path override.
   *
   * @return the pinned path name, or {@code auto} when selection is by batch size
   */
  public String forcedPath() {
    return properties.ingestion().hasOverride()
        ? properties.ingestion().forcePath().trim().toLowerCase()
        : "auto";
  }

  /**
   * Decide which path a batch of the given size takes.
   *
   * @param rows number of points in the request
   * @return the selected path
   */
  public Route selectRoute(int rows) {
    MetricPlatformProperties.Ingestion ingestion = properties.ingestion();
    if (ingestion.hasOverride()) {
      boolean copy = "copy".equalsIgnoreCase(ingestion.forcePath().trim());
      return copy ? Route.COPY : Route.BATCH;
    }
    return rows >= ingestion.copyMinBatchSize() ? Route.COPY : Route.BATCH;
  }

  /** Which write path served a request. */
  public enum Route {

    /** JDBC batch of independent {@code INSERT} statements. */
    BATCH,

    /** One {@code COPY FROM STDIN} stream. */
    COPY
  }

  /**
   * Outcome of one ingestion call.
   *
   * @param route write path taken
   * @param written number of rows written
   */
  public record IngestResult(Route route, int written) {
  }

  /**
   * One ingestion observation, for display.
   *
   * @param route write path taken
   * @param rows rows in that request
   * @param at when it happened
   */
  public record RouteRecord(Route route, int rows, Instant at) {
  }
}
