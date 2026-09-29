package com.example.metrics.bench;

import com.example.metrics.store.CopyMetricStore;
import com.example.metrics.store.MetricStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compares the two ingestion paths against the same table with the same data:
 * {@code JdbcTemplate.batchUpdate} (one {@code INSERT} per row) versus
 * {@code COPY FROM STDIN}.
 *
 * <p>Only the JDBC call is inside the timed window. Point generation, table
 * truncation, and connection setup all happen outside it, so the difference
 * reported is the write path and nothing else.
 *
 * <p>Run:
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=com.example.metrics.bench.WritePathBenchmark \
 *       -Dexec.args="100000 3"
 * </pre>
 */
public final class WritePathBenchmark {

  private static final String[] METRICS = {
      "cpu.usage", "mem.usage", "http.latency.p95", "http.qps", "http.error.rate"
  };

  private static final int[] BATCH_SIZES = {1, 100, 1000, 10000};

  private WritePathBenchmark() {
  }

  /**
   * Run the comparison.
   *
   * @param args optional {@code <totalRows> <repetitions>}
   * @throws Exception when the datasource cannot be built
   */
  public static void main(String[] args) throws Exception {
    int totalRows = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
    int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 3;

    String url = env("DB_URL", "jdbc:postgresql://127.0.0.1:5432/metrics");
    String user = env("DB_USER", "metrics");
    String password = env("DB_PASSWORD", "metrics");
    // Set BENCH_REWRITE_BATCHED_INSERTS to true or false to control the driver's
    // INSERT rewriting. The default behaviour of the driver is measured when the
    // variable is absent, which is what a production deployment gets.
    String rewrite = System.getenv("BENCH_REWRITE_BATCHED_INSERTS");

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(4);
    config.setAutoCommit(true);
    if (rewrite != null && !rewrite.isBlank()) {
      config.addDataSourceProperty("reWriteBatchedInserts", rewrite);
    }

    try (HikariDataSource dataSource = new HikariDataSource(config)) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      MetricStore batchStore = new MetricStore(jdbc);
      CopyMetricStore copyStore = new CopyMetricStore(dataSource);

      System.out.printf("rows per configuration : %,d%n", totalRows);
      System.out.printf("repetitions            : %d (median reported)%n", repetitions);
      System.out.printf("target                 : %s%n", url);
      System.out.printf("reWriteBatchedInserts  : %s%n%n",
          rewrite == null ? "(driver default)" : rewrite);

      List<MetricStore.MetricPoint> points = generate(totalRows);
      List<Row> results = new ArrayList<>();

      for (int batchSize : BATCH_SIZES) {
        results.add(measure("batchUpdate", batchSize, totalRows, repetitions,
            chunk -> batchStore.insertBatch(chunk), jdbc, points));
        results.add(measure("COPY", batchSize, totalRows, repetitions,
            chunk -> copyStore.copyIn(chunk), jdbc, points));
      }

      printReport(results);
    }
  }

  private static Row measure(
      String path,
      int batchSize,
      int totalRows,
      int repetitions,
      Inserter inserter,
      JdbcTemplate jdbc,
      List<MetricStore.MetricPoint> points) {

    List<Double> samples = new ArrayList<>();
    for (int run = 0; run <= repetitions; run++) {
      jdbc.execute("TRUNCATE metric_point");
      long start = System.nanoTime();
      for (int offset = 0; offset < points.size(); offset += batchSize) {
        List<MetricStore.MetricPoint> chunk =
            points.subList(offset, Math.min(offset + batchSize, points.size()));
        inserter.insert(chunk);
      }
      double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
      // Run 0 warms the JIT and the server's relation cache; its sample is dropped.
      if (run > 0) {
        samples.add(seconds);
      }
    }

    jdbc.execute("TRUNCATE metric_point");
    samples.sort(Double::compare);
    double median = samples.get(samples.size() / 2);
    System.out.printf("  %-12s batch=%-6d median %7.3f s  ->  %,10.0f rows/s%n",
        path, batchSize, median, totalRows / median);
    return new Row(path, batchSize, median, totalRows / median);
  }

  private static void printReport(List<Row> results) {
    System.out.println();
    System.out.println("path         | batch | median (s) | rows/s    | vs batchUpdate@same");
    System.out.println("-------------|-------|------------|-----------|--------------------");
    for (Row row : results) {
      double baseline = results.stream()
          .filter(other -> other.batchSize() == row.batchSize()
              && "batchUpdate".equals(other.path()))
          .findFirst()
          .map(Row::rowsPerSecond)
          .orElse(row.rowsPerSecond());
      System.out.printf("%-12s | %5d | %10.3f | %9.0f | %.2fx%n",
          row.path(), row.batchSize(), row.medianSeconds(), row.rowsPerSecond(),
          row.rowsPerSecond() / baseline);
    }

    double bestBatch = results.stream()
        .filter(row -> "batchUpdate".equals(row.path()))
        .mapToDouble(Row::rowsPerSecond).max().orElse(0);
    double bestCopy = results.stream()
        .filter(row -> "COPY".equals(row.path()))
        .mapToDouble(Row::rowsPerSecond).max().orElse(0);
    if (bestBatch > 0) {
      System.out.printf("%nbest batchUpdate %,.0f rows/s  vs  best COPY %, .0f rows/s  ->  %.1fx%n",
          bestBatch, bestCopy, bestCopy / bestBatch);
    }
  }

  private static List<MetricStore.MetricPoint> generate(int count) {
    Random random = new Random(20260923L);
    Instant end = Instant.parse("2026-09-23T12:00:00Z");
    List<MetricStore.MetricPoint> points = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      String metric = METRICS[index % METRICS.length];
      Map<String, String> tags = new LinkedHashMap<>();
      tags.put("host", "host-" + (index % 5 + 1));
      points.add(new MetricStore.MetricPoint(
          metric,
          end.minusSeconds(count - index),
          40 + random.nextGaussian() * 12,
          tags));
    }
    return points;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Inserts one chunk; the only difference between the two measured paths. */
  @FunctionalInterface
  private interface Inserter {
    int insert(List<MetricStore.MetricPoint> chunk);
  }

  /**
   * One measured configuration.
   *
   * @param path ingestion path name
   * @param batchSize rows per JDBC call
   * @param medianSeconds median wall time for the whole data set
   * @param rowsPerSecond derived throughput
   */
  private record Row(String path, int batchSize, double medianSeconds, double rowsPerSecond) {
  }
}
