package com.example.metrics.store;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes for {@code metric_point}. Queries are written as SQL rather
 * than through an ORM because every read here is an aggregate over a time
 * bucket, which an ORM would only obscure.
 */
@Repository
public class MetricStore {

  private final JdbcTemplate jdbc;

  /**
   * Create the store.
   *
   * @param jdbc configured template bound to the metrics datasource
   */
  public MetricStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert a batch of points in a single round trip.
   *
   * @param points points to insert, already validated
   * @return number of rows inserted
   */
  @Transactional
  public int insertBatch(List<MetricPoint> points) {
    if (points.isEmpty()) {
      return 0;
    }
    String sql = """
        INSERT INTO metric_point (metric_name, tags, ts, value)
        VALUES (?, CAST(? AS jsonb), ?, ?)
        """;
    jdbc.batchUpdate(sql, points, points.size(), (ps, point) -> {
      ps.setString(1, point.name());
      ps.setString(2, toJson(point.tags()));
      ps.setTimestamp(3, Timestamp.from(point.ts()));
      ps.setDouble(4, point.value());
    });
    return points.size();
  }

  /**
   * Aggregate one metric into fixed-width time buckets.
   *
   * <p>Bucketing divides the epoch second by the bucket width and multiplies
   * back, which supports any width. {@code date_trunc} only accepts fixed units
   * such as minute or hour.
   *
   * @param name metric to aggregate
   * @param from inclusive start of the range
   * @param to exclusive end of the range
   * @param bucketSeconds bucket width in seconds
   * @return one row per non-empty bucket, ordered by time
   */
  public List<SeriesPoint> queryBucketed(String name, Instant from, Instant to, int bucketSeconds) {
    String sql = """
        SELECT to_timestamp(floor(extract(epoch FROM ts) / ?) * ?) AS bucket,
               avg(value) AS avg_v,
               max(value) AS max_v,
               min(value) AS min_v,
               count(*)   AS c
        FROM metric_point
        WHERE metric_name = ? AND ts >= ? AND ts < ?
        GROUP BY bucket
        ORDER BY bucket
        """;
    return jdbc.query(sql,
        (rs, rowNum) -> new SeriesPoint(
            rs.getTimestamp("bucket").toInstant(),
            rs.getDouble("avg_v"),
            rs.getDouble("max_v"),
            rs.getDouble("min_v"),
            rs.getLong("c")),
        bucketSeconds, bucketSeconds, name, Timestamp.from(from), Timestamp.from(to));
  }

  /**
   * Latest value of a metric, used by alert evaluation.
   *
   * @param name metric to read
   * @return the newest value, or empty when the metric has no points yet
   */
  public java.util.Optional<MetricPoint> latest(String name) {
    // The id tiebreaker matters: agents batch points and several can share a
    // timestamp, and without it the row that comes back for a given ts is
    // whichever the planner happens to return first.
    String sql = """
        SELECT metric_name, tags, ts, value
        FROM metric_point
        WHERE metric_name = ?
        ORDER BY ts DESC, id DESC
        LIMIT 1
        """;
    List<MetricPoint> rows = jdbc.query(sql, (rs, rowNum) -> new MetricPoint(
        rs.getString("metric_name"),
        rs.getTimestamp("ts").toInstant(),
        rs.getDouble("value"),
        parseTags(rs.getString("tags"))), name);
    return rows.stream().findFirst();
  }

  /**
   * Mean and population standard deviation over a recent window, used to build
   * a statistical bound.
   *
   * @param name metric to summarise
   * @param window lookback duration ending now
   * @return the summary, or empty when the window holds fewer than two points
   */
  public java.util.Optional<Baseline> baseline(String name, Duration window) {
    String sql = """
        SELECT avg(value) AS mean_v,
               coalesce(stddev_pop(value), 0) AS sd_v,
               count(*) AS c
        FROM metric_point
        WHERE metric_name = ? AND ts >= now() - (? * INTERVAL '1 second')
        """;
    List<Baseline> rows = jdbc.query(sql, (rs, rowNum) -> new Baseline(
        rs.getDouble("mean_v"),
        rs.getDouble("sd_v"),
        rs.getLong("c")), name, window.toSeconds());
    return rows.stream().filter(row -> row.sampleCount() >= 2).findFirst();
  }

  /**
   * Delete at most one batch of expired points.
   *
   * <p>The batch bound keeps the delete transaction short; the scheduler calls
   * this repeatedly until it returns zero.
   *
   * @param retention how long points are kept
   * @param batchSize maximum rows to delete in this call
   * @return number of rows deleted
   */
  @Transactional
  public int deleteExpired(Duration retention, int batchSize) {
    String sql = """
        DELETE FROM metric_point
        WHERE id IN (
          SELECT id FROM metric_point
          WHERE ts < now() - (? * INTERVAL '1 second')
          LIMIT ?
        )
        """;
    return jdbc.update(sql, retention.toSeconds(), batchSize);
  }

  /**
   * Count stored points, for the smoke test and the dashboard.
   *
   * @return total row count
   */
  public long count() {
    Long total = jdbc.queryForObject("SELECT count(*) FROM metric_point", Long.class);
    return total == null ? 0L : total;
  }

  /**
   * Distinct metric names with their point counts.
   *
   * @return one entry per metric, ordered by name
   */
  public List<Map<String, Object>> listMetrics() {
    return jdbc.queryForList("""
        SELECT metric_name AS name, count(*) AS points, max(ts) AS last_seen
        FROM metric_point
        GROUP BY metric_name
        ORDER BY metric_name
        """);
  }

  private static String toJson(Map<String, String> tags) {
    if (tags == null || tags.isEmpty()) {
      return "{}";
    }
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : tags.entrySet()) {
      if (!first) {
        json.append(',');
      }
      json.append('"').append(escape(entry.getKey())).append("\":\"")
          .append(escape(entry.getValue())).append('"');
      first = false;
    }
    return json.append('}').toString();
  }

  private static String escape(String raw) {
    return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static Map<String, String> parseTags(String json) {
    if (json == null || json.isBlank() || "{}".equals(json)) {
      return Map.of();
    }
    // The dashboard only needs a flat string map; a full JSON parser is not
    // worth a dependency for a field this narrow.
    String body = json.trim().replaceAll("^\\{|\\}$", "");
    if (body.isBlank()) {
      return Map.of();
    }
    java.util.LinkedHashMap<String, String> tags = new java.util.LinkedHashMap<>();
    for (String pair : body.split(",")) {
      String[] kv = pair.split(":", 2);
      if (kv.length == 2) {
        tags.put(unquote(kv[0]), unquote(kv[1]));
      }
    }
    return tags;
  }

  private static String unquote(String raw) {
    return raw.trim().replaceAll("^\"|\"$", "");
  }

  /**
   * One stored metric point.
   *
   * @param name metric name
   * @param ts sample time
   * @param value sample value
   * @param tags flat label map
   */
  public record MetricPoint(String name, Instant ts, double value, Map<String, String> tags) {
  }

  /**
   * One aggregated bucket.
   *
   * @param bucket bucket start instant
   * @param avg average value in the bucket
   * @param max maximum value in the bucket
   * @param min minimum value in the bucket
   * @param count number of raw points in the bucket
   */
  public record SeriesPoint(Instant bucket, double avg, double max, double min, long count) {
  }

  /**
   * Statistical summary of a recent window.
   *
   * @param mean arithmetic mean
   * @param stddev population standard deviation
   * @param sampleCount number of points in the window
   */
  public record Baseline(double mean, double stddev, long sampleCount) {
  }
}
