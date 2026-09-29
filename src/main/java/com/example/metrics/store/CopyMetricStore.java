package com.example.metrics.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.stereotype.Repository;

/**
 * Bulk ingestion through PostgreSQL's {@code COPY FROM STDIN} protocol.
 *
 * <p>This bypasses the parser, planner, and executor that a batch of
 * {@code INSERT} statements would each pay for, at the cost of the features
 * those layers provide: no {@code ON CONFLICT}, no {@code RETURNING}, no
 * per-row trigger evaluation, and one failure position for the whole batch.
 * Metric ingestion needs none of those, which is why it takes this path.
 *
 * <p>Encoding is text format with tab separators. The text format costs some
 * bytes on the wire compared with binary, but it is the only one of the two
 * that does not require the caller to know the exact binary layout of each
 * column type.
 */
@Repository
public class CopyMetricStore {

  /** PostgreSQL text-format COPY terminator for a stream with no end marker. */
  private static final String COPY_END = "\\.";

  private final DataSource dataSource;

  /**
   * Create the store.
   *
   * @param dataSource datasource the COPY stream borrows a connection from
   */
  public CopyMetricStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Insert a batch of points in one COPY stream.
   *
   * <p>Deliberately not {@code @Transactional}: the stream borrows its own
   * connection instead of the transaction-bound one, so an outer transaction
   * would not cover it and the annotation would only mislead a reader. A single
   * {@code COPY} is atomic on its own, which is the guarantee this path offers
   * — there is no partial-batch rollback, and the whole statement fails or none
   * of it does.
   *
   * @param points points to insert
   * @return number of rows the server reported as copied
   * @throws IllegalStateException when no physical PostgreSQL connection can be obtained
   */
  public int copyIn(List<MetricStore.MetricPoint> points) {
    if (points.isEmpty()) {
      return 0;
    }
    try (Connection connection = dataSource.getConnection()) {
      PGConnection pg = connection.unwrap(PGConnection.class);
      CopyManager copyManager = pg.getCopyAPI();
      String sql = "COPY metric_point (metric_name, tags, ts, value) FROM STDIN";
      long copied = copyManager.copyIn(sql, new java.io.StringReader(payload(points)));
      return (int) copied;
    } catch (SQLException failure) {
      throw new IllegalStateException("COPY ingestion failed", failure);
    } catch (java.io.IOException failure) {
      // StringReader over an in-memory string cannot fail in practice; the
      // reader interface still declares it.
      throw new IllegalStateException("COPY payload could not be read", failure);
    }
  }

  private static String payload(List<MetricStore.MetricPoint> points) {
    StringBuilder out = new StringBuilder(points.size() * 96);
    for (MetricStore.MetricPoint point : points) {
      out.append(escape(point.name())).append('\t')
          .append(escape(tagsToJson(point.tags()))).append('\t')
          .append(OffsetDateTime.ofInstant(point.ts(), ZoneOffset.UTC)).append('\t')
          .append(point.value()).append('\n');
    }
    out.append(COPY_END).append('\n');
    return out.toString();
  }

  /**
   * Escape one COPY text-format field.
   *
   * <p>The text format treats backslash as the escape character, so a literal
   * backslash must be doubled before the characters that would otherwise start
   * an escape sequence.
   *
   * @param raw field value, may contain tabs, newlines, or backslashes
   * @return the value safe to place between tab separators
   */
  static String escape(String raw) {
    if (raw == null) {
      return "\\N";
    }
    return raw.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  /**
   * Render the tag map as the JSON text the {@code jsonb} column expects.
   *
   * @param tags flat label map, may be null
   * @return JSON object text
   */
  static String tagsToJson(Map<String, String> tags) {
    if (tags == null || tags.isEmpty()) {
      return "{}";
    }
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : tags.entrySet()) {
      if (!first) {
        json.append(',');
      }
      json.append('"').append(escapeJson(entry.getKey())).append("\":\"")
          .append(escapeJson(entry.getValue())).append('"');
      first = false;
    }
    return json.append('}').toString();
  }

  private static String escapeJson(String raw) {
    return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
