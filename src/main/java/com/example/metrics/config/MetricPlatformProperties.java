package com.example.metrics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-varying settings. Anything an operator may need to change lives
 * here rather than as a constant in the code.
 *
 * @param retentionDays how long raw metric points are kept
 * @param retentionBatchSize rows deleted per retention pass, to keep the delete transaction short
 * @param maxPointAgeSeconds newest point age beyond which a metric is treated as stale
 * @param ingestion write path selection
 * @param alerts alert evaluation and delivery settings
 * @param security credentials for the two kinds of caller
 * @param cors cross-origin settings for the dashboard dev server
 */
@ConfigurationProperties(prefix = "metric-platform")
public record MetricPlatformProperties(
    int retentionDays,
    int retentionBatchSize,
    int maxPointAgeSeconds,
    Ingestion ingestion,
    Alerts alerts,
    Security security,
    Cors cors) {

  /**
   * Write path selection for metric ingestion.
   *
   * <p>COPY and batch {@code INSERT} cross over somewhere around a hundred rows
   * per call: below that, COPY's fixed per-call protocol setup costs more than
   * the per-row parser and planner work it avoids. The threshold is the measured
   * crossover, not a preference.
   *
   * @param copyMinBatchSize rows per request at which COPY takes over
   * @param forcePath optional override: {@code copy} or {@code batch}; empty selects by size
   */
  public record Ingestion(int copyMinBatchSize, String forcePath) {

    /**
     * Whether COPY was pinned on or off regardless of batch size.
     *
     * @return true when an override is configured
     */
    public boolean hasOverride() {
      return forcePath != null && !forcePath.isBlank() && !"auto".equalsIgnoreCase(forcePath);
    }
  }

  /**
   * Alert evaluation and notification settings.
   *
   * @param enabled whether the scheduler evaluates rules at all
   * @param evaluationIntervalMs delay between evaluation passes
   * @param notifyTo recipient of fired alerts; empty disables mail delivery
   * @param notifyFrom envelope sender for alert mail
   */
  public record Alerts(
      boolean enabled,
      long evaluationIntervalMs,
      String notifyTo,
      String notifyFrom) {
  }

  /**
   * Credentials for the two kinds of caller.
   *
   * <p>They are separate because they face different exposure. An agent runs on
   * a host you control and can hold a long-lived key; the dashboard runs in a
   * browser where anything shipped in the JavaScript is readable by whoever
   * opens devtools, so it carries a credential the operator supplies at runtime
   * instead.
   *
   * @param apiKey value agents present in the {@code X-API-Key} header; blank
   *     restricts ingestion to loopback requests
   * @param dashboardUser login accepted by the read and rule-management endpoints
   * @param dashboardPassword login accepted by the read and rule-management
   *     endpoints; blank generates a password for this run and logs it
   * @param maxAuthFailures failed logins from one address before it is refused
   * @param authFailureWindowSeconds how far back a failed login still counts
   */
  public record Security(
      String apiKey,
      String dashboardUser,
      String dashboardPassword,
      int maxAuthFailures,
      int authFailureWindowSeconds) {

    /**
     * Whether an API key was configured.
     *
     * @return true when agents must present a key
     */
    public boolean hasApiKey() {
      return apiKey != null && !apiKey.isBlank();
    }
  }

  /**
   * Dashboard origin settings.
   *
   * @param allowedOrigins comma-separated origins allowed to call the API
   */
  public record Cors(String allowedOrigins) {
  }
}
