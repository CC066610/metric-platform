package com.example.metrics.store;

import com.example.metrics.domain.AlertEvent;
import com.example.metrics.domain.AlertRule;
import com.example.metrics.domain.AlertState;
import com.example.metrics.domain.AlertStatus;
import com.example.metrics.domain.Comparison;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for alert rules, machine state, and alert history. */
@Repository
public class AlertStore {

  private static final Logger log = LoggerFactory.getLogger(AlertStore.class);

  private final JdbcTemplate jdbc;

  /**
   * Create the store.
   *
   * @param jdbc configured template bound to the metrics datasource
   */
  public AlertStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Load every enabled rule.
   *
   * @return enabled rules ordered by identity
   */
  public List<AlertRule> enabledRules() {
    return jdbc.query("""
        SELECT id, metric_name, operator, threshold, sigma_multiplier,
               window_seconds, min_consecutive, cooldown_seconds, enabled
        FROM alert_rule
        WHERE enabled = TRUE
        ORDER BY id
        """, (rs, rowNum) -> new AlertRule(
        rs.getLong("id"),
        rs.getString("metric_name"),
        Comparison.from(rs.getString("operator")),
        rs.getDouble("threshold"),
        (Double) rs.getObject("sigma_multiplier"),
        rs.getInt("window_seconds"),
        rs.getInt("min_consecutive"),
        rs.getInt("cooldown_seconds"),
        rs.getBoolean("enabled")));
  }

  /**
   * Read current state for one rule.
   *
   * @param ruleId rule identity
   * @return stored state, or a fresh {@link AlertState#initial(long)} when the rule has none
   */
  public AlertState loadState(long ruleId) {
    List<AlertState> rows = jdbc.query("""
        SELECT rule_id, status, consecutive, last_fired_at, last_value
        FROM alert_state
        WHERE rule_id = ?
        """, (rs, rowNum) -> new AlertState(
        rs.getLong("rule_id"),
        AlertStatus.from(rs.getString("status")),
        rs.getInt("consecutive"),
        toInstant(rs.getTimestamp("last_fired_at")),
        (Double) rs.getObject("last_value")), ruleId);
    return rows.stream().findFirst().orElseGet(() -> AlertState.initial(ruleId));
  }

  /**
   * Upsert one rule's state.
   *
   * @param state state to persist
   */
  public void saveState(AlertState state) {
    jdbc.update("""
        INSERT INTO alert_state (rule_id, status, consecutive, last_fired_at, last_value, updated_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (rule_id) DO UPDATE SET
          status = EXCLUDED.status,
          consecutive = EXCLUDED.consecutive,
          last_fired_at = EXCLUDED.last_fired_at,
          last_value = EXCLUDED.last_value,
          updated_at = now()
        """,
        state.ruleId(),
        state.status().name().toLowerCase(),
        state.consecutive(),
        state.lastFiredAt() == null ? null : Timestamp.from(state.lastFiredAt()),
        state.lastValue());
  }

  /**
   * Append a transition to alert history.
   *
   * @param event event to record
   */
  public void recordEvent(AlertEvent event) {
    jdbc.update("""
        INSERT INTO alert_event (rule_id, metric_name, kind, observed, threshold, message, fired_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        event.ruleId(),
        event.metricName(),
        event.kind().name().toLowerCase(),
        event.observed(),
        event.threshold(),
        event.message(),
        Timestamp.from(event.at()));
  }

  /**
   * Recent alert history, newest first.
   *
   * @param limit maximum rows to return
   * @return history entries
   */
  public List<AlertEvent> recentEvents(int limit) {
    return jdbc.query("""
        SELECT rule_id, metric_name, kind, observed, threshold, message, fired_at
        FROM alert_event
        ORDER BY fired_at DESC
        LIMIT ?
        """, (rs, rowNum) -> new AlertEvent(
        rs.getLong("rule_id"),
        rs.getString("metric_name"),
        "resolved".equalsIgnoreCase(rs.getString("kind"))
            ? AlertEvent.Kind.RESOLVED
            : AlertEvent.Kind.FIRING,
        rs.getDouble("observed"),
        rs.getDouble("threshold"),
        rs.getString("message"),
        rs.getTimestamp("fired_at").toInstant()), limit);
  }

  /**
   * Current state of every rule, joined with the rule definition for display.
   *
   * @return one row per rule
   */
  public List<RuleStatusView> ruleStatuses() {
    return jdbc.query("""
        SELECT r.id, r.metric_name, r.operator, r.threshold, r.sigma_multiplier,
               r.min_consecutive, r.cooldown_seconds,
               coalesce(s.status, 'ok') AS status,
               coalesce(s.consecutive, 0) AS consecutive,
               s.last_fired_at, s.last_value
        FROM alert_rule r
        LEFT JOIN alert_state s ON s.rule_id = r.id
        ORDER BY r.id
        """, (rs, rowNum) -> new RuleStatusView(
        rs.getLong("id"),
        rs.getString("metric_name"),
        rs.getString("operator"),
        rs.getDouble("threshold"),
        (Double) rs.getObject("sigma_multiplier"),
        rs.getInt("min_consecutive"),
        rs.getInt("cooldown_seconds"),
        rs.getString("status"),
        rs.getInt("consecutive"),
        toInstant(rs.getTimestamp("last_fired_at")),
        (Double) rs.getObject("last_value")));
  }

  /**
   * Create a rule and its initial state.
   *
   * @param metricName metric to watch
   * @param operator {@code gt} or {@code lt}
   * @param threshold absolute bound
   * @param sigmaMultiplier statistical multiplier, or null for an absolute bound
   * @param windowSeconds sigma lookback
   * @param minConsecutive consecutive breaches required before firing
   * @param cooldownSeconds quiet period after firing
   * @return identity of the created rule
   */
  public long createRule(
      String metricName,
      String operator,
      double threshold,
      Double sigmaMultiplier,
      int windowSeconds,
      int minConsecutive,
      int cooldownSeconds) {
    Long id = jdbc.queryForObject("""
        INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier,
                                window_seconds, min_consecutive, cooldown_seconds)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """, Long.class, metricName, operator, threshold, sigmaMultiplier,
        windowSeconds, minConsecutive, cooldownSeconds);
    long ruleId = id == null ? 0L : id;
    jdbc.update("INSERT INTO alert_state (rule_id) VALUES (?) ON CONFLICT DO NOTHING", ruleId);
    return ruleId;
  }

  /**
   * Delete a rule.
   *
   * @param ruleId rule identity
   * @return number of rows deleted
   */
  public int deleteRule(long ruleId) {
    return jdbc.update("DELETE FROM alert_rule WHERE id = ?", ruleId);
  }

  /**
   * Enable or disable a rule.
   *
   * @param ruleId rule identity
   * @param enabled new value
   * @return number of rows updated
   */
  public int setEnabled(long ruleId, boolean enabled) {
    return jdbc.update("UPDATE alert_rule SET enabled = ? WHERE id = ?", enabled, ruleId);
  }

  /**
   * Whether a quiet window currently covers the given local time.
   *
   * <p>A window whose stored text cannot be parsed is skipped rather than
   * thrown: this runs inside the evaluation pass, so one malformed row would
   * otherwise abort every rule's evaluation, every pass, until someone edited
   * the database.
   *
   * @param now local time to test
   * @return true when notifications should be suppressed
   */
  public boolean isQuietNow(Instant now) {
    java.time.LocalTime localNow = java.time.LocalTime.ofInstant(now, java.time.ZoneId.systemDefault());
    List<String[]> windows = jdbc.query(
        "SELECT start_hhmm, end_hhmm FROM quiet_window WHERE enabled = TRUE",
        (rs, rowNum) -> new String[] {rs.getString("start_hhmm"), rs.getString("end_hhmm")});
    for (String[] window : windows) {
      java.time.LocalTime start;
      java.time.LocalTime end;
      try {
        start = java.time.LocalTime.parse(window[0]);
        end = java.time.LocalTime.parse(window[1]);
      } catch (java.time.format.DateTimeParseException malformed) {
        log.warn("ignoring quiet window with unparseable bounds: {} - {}", window[0], window[1]);
        continue;
      }
      if (end.isBefore(start)) {
        // Window crosses midnight, e.g. 23:00 to 07:00.
        if (!localNow.isBefore(start) || localNow.isBefore(end)) {
          return true;
        }
      } else if (!localNow.isBefore(start) && localNow.isBefore(end)) {
        return true;
      }
    }
    return false;
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  /**
   * Rule plus its live state, for the dashboard.
   *
   * @param id rule identity
   * @param metricName metric watched
   * @param operator bound direction
   * @param threshold absolute bound
   * @param sigmaMultiplier statistical multiplier when configured
   * @param minConsecutive breach count required before firing
   * @param cooldownSeconds quiet period after firing
   * @param status current lifecycle position
   * @param consecutive breaching evaluations so far
   * @param lastFiredAt last notification time
   * @param lastValue most recent observation
   */
  public record RuleStatusView(
      long id,
      String metricName,
      String operator,
      double threshold,
      Double sigmaMultiplier,
      int minConsecutive,
      int cooldownSeconds,
      String status,
      int consecutive,
      Instant lastFiredAt,
      Double lastValue) {
  }
}
