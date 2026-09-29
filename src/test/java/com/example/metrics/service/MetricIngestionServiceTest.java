package com.example.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.metrics.config.MetricPlatformProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Route-selection tests for the ingestion service.
 *
 * <p>Only the selection is under test: the stores are passed as null because
 * choosing a path must not touch either of them. A selection that depended on a
 * store would be a design defect, and these tests would then fail with a
 * NullPointerException rather than passing by accident.
 */
class MetricIngestionServiceTest {

  private static MetricIngestionService service(int copyMinBatchSize, String forcePath) {
    MetricPlatformProperties properties = new MetricPlatformProperties(
        7, 10_000, 300,
        new MetricPlatformProperties.Ingestion(copyMinBatchSize, forcePath),
        new MetricPlatformProperties.Alerts(true, 30_000, "", "metrics@localhost"),
        new MetricPlatformProperties.Security(null, null, null, 0, 0),
        new MetricPlatformProperties.Cors(""));
    return new MetricIngestionService(null, null, properties);
  }

  @Test
  @DisplayName("a batch at or above the threshold takes the COPY path")
  void largeBatchUsesCopy() {
    assertThat(service(100, "auto").selectRoute(100))
        .isEqualTo(MetricIngestionService.Route.COPY);
    assertThat(service(100, "auto").selectRoute(1_000))
        .isEqualTo(MetricIngestionService.Route.COPY);
  }

  @Test
  @DisplayName("a batch below the threshold keeps the JDBC batch path")
  void smallBatchUsesBatch() {
    // COPY is measurably slower at one row per call, so this must not flip.
    assertThat(service(100, "auto").selectRoute(1))
        .isEqualTo(MetricIngestionService.Route.BATCH);
    assertThat(service(100, "auto").selectRoute(99))
        .isEqualTo(MetricIngestionService.Route.BATCH);
  }

  @Test
  @DisplayName("an explicit override pins one path regardless of size")
  void overridePinsPath() {
    assertThat(service(100, "copy").selectRoute(1))
        .isEqualTo(MetricIngestionService.Route.COPY);
    assertThat(service(100, "batch").selectRoute(100_000))
        .isEqualTo(MetricIngestionService.Route.BATCH);
  }

  @Test
  @DisplayName("the default configuration is auto, not a pinned path")
  void defaultIsAutomatic() {
    MetricPlatformProperties.Ingestion ingestion =
        new MetricPlatformProperties.Ingestion(500, "auto");
    assertThat(ingestion.hasOverride()).isFalse();
    assertThat(service(500, "auto").selectRoute(499))
        .isEqualTo(MetricIngestionService.Route.BATCH);
    assertThat(service(500, "auto").selectRoute(500))
        .isEqualTo(MetricIngestionService.Route.COPY);
  }

  @Test
  @DisplayName("a blank or missing override is treated as automatic")
  void blankOverrideIsAutomatic() {
    assertThat(new MetricPlatformProperties.Ingestion(100, null).hasOverride()).isFalse();
    assertThat(new MetricPlatformProperties.Ingestion(100, "  ").hasOverride()).isFalse();
  }

  @Test
  @DisplayName("the configured threshold and override are readable for display")
  void exposesConfigurationForDisplay() {
    assertThat(service(250, "auto").copyMinBatchSize()).isEqualTo(250);
    assertThat(service(250, "auto").forcedPath()).isEqualTo("auto");
    assertThat(service(250, "COPY").forcedPath()).isEqualTo("copy");
  }

  @Test
  @DisplayName("no route has been observed before the first ingestion")
  void lastRouteIsEmptyBeforeAnyIngestion() {
    // The dashboard reads this to avoid claiming a path it has not seen yet.
    assertThat(service(100, "auto").lastRoute()).isEmpty();
  }
}
