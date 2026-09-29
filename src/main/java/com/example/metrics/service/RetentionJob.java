package com.example.metrics.service;

import com.example.metrics.config.MetricPlatformProperties;
import com.example.metrics.store.MetricStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Deletes metric points that fell outside the retention window.
 *
 * <p>Without this the demo data set grows without bound, which shows up first as
 * slow dashboard queries and finally as a benchmark that measures the wrong
 * thing. Deletion runs in bounded batches so no single transaction holds locks
 * long enough to stall ingestion.
 */
@Service
public class RetentionJob {

  private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

  private final MetricStore metrics;
  private final MetricPlatformProperties properties;

  /**
   * Create the job.
   *
   * @param metrics metric persistence
   * @param properties deployment settings, including retention window and batch size
   */
  public RetentionJob(MetricStore metrics, MetricPlatformProperties properties) {
    this.metrics = metrics;
    this.properties = properties;
  }

  /** Run one retention pass every hour, draining batches until nothing is expired. */
  @Scheduled(fixedDelay = 3_600_000L, initialDelay = 120_000L)
  public void purgeExpired() {
    long total = 0;
    int batch;
    do {
      batch = metrics.deleteExpired(
          Duration.ofDays(properties.retentionDays()), properties.retentionBatchSize());
      total += batch;
    } while (batch == properties.retentionBatchSize());

    if (total > 0) {
      log.info("retention: deleted {} points older than {} days",
          total, properties.retentionDays());
    }
  }
}
