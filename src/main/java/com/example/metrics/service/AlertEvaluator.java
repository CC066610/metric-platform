package com.example.metrics.service;

import com.example.metrics.config.MetricPlatformProperties;
import com.example.metrics.domain.AlertEvaluation;
import com.example.metrics.domain.AlertEvent;
import com.example.metrics.domain.AlertRule;
import com.example.metrics.domain.AlertState;
import com.example.metrics.store.AlertStore;
import com.example.metrics.store.MetricStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates every enabled rule against the latest observation and persists both
 * the resulting state and any alert history entry.
 *
 * <p>Statistics are computed in PostgreSQL rather than in the Java process:
 * the baseline needs an aggregate over a window, and shipping raw points into
 * the application to average them would move the whole window over the wire.
 */
@Service
public class AlertEvaluator {

  private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

  private final AlertStore alerts;
  private final MetricStore metrics;
  private final AlertStateMachine machine;
  private final AlertNotifier notifier;
  private final MetricPlatformProperties properties;

  /**
   * Create the evaluator.
   *
   * @param alerts alert persistence
   * @param metrics metric persistence
   * @param machine decision logic
   * @param notifier asynchronous notification delivery
   * @param properties deployment settings
   */
  public AlertEvaluator(
      AlertStore alerts,
      MetricStore metrics,
      AlertStateMachine machine,
      AlertNotifier notifier,
      MetricPlatformProperties properties) {
    this.alerts = alerts;
    this.metrics = metrics;
    this.machine = machine;
    this.notifier = notifier;
    this.properties = properties;
  }

  /**
   * Evaluate all enabled rules on the configured interval.
   */
  @Scheduled(fixedDelayString = "${metric-platform.alerts.evaluation-interval-ms:30000}")
  public void evaluateAll() {
    if (!properties.alerts().enabled()) {
      return;
    }
    List<AlertEvaluation> produced = evaluateOnce(Instant.now());
    produced.stream()
        .map(AlertEvaluation::event)
        .flatMap(Optional::stream)
        .forEach(this::deliver);
  }

  /**
   * Run one evaluation pass over every enabled rule.
   *
   * @param now evaluation time, injected so tests can control the cooldown window
   * @return every evaluation that recorded history, in rule order
   */
  @Transactional
  public List<AlertEvaluation> evaluateOnce(Instant now) {
    List<AlertRule> rules = alerts.enabledRules();
    List<AlertEvaluation> recorded = new ArrayList<>();

    for (AlertRule rule : rules) {
      Optional<MetricStore.MetricPoint> latest = metrics.latest(rule.metricName());
      if (latest.isEmpty()) {
        continue;
      }
      MetricStore.MetricPoint point = latest.get();

      if (isStale(point, now)) {
        log.debug("skipping rule {} for {}: newest point is {}s old",
            rule.id(), rule.metricName(), Duration.between(point.ts(), now).toSeconds());
        continue;
      }

      Optional<AlertStateMachine.Bound> bound = resolveBound(rule);
      if (bound.isEmpty()) {
        // A statistical rule with too few samples cannot be judged yet. Skipping
        // is correct; inventing a bound would produce a false alert.
        log.debug("skipping rule {} for {}: baseline unavailable", rule.id(), rule.metricName());
        continue;
      }

      AlertState state = alerts.loadState(rule.id());
      AlertEvaluation evaluation =
          machine.evaluate(rule, state, bound.get(), point.value(), now);
      alerts.saveState(evaluation.state());

      evaluation.event().ifPresent(event -> {
        alerts.recordEvent(event);
        recorded.add(evaluation);
        log.info("alert {} rule={} metric={} observed={} bound={}",
            event.kind(), rule.id(), rule.metricName(), event.observed(), event.threshold());
      });
    }
    return recorded;
  }

  /**
   * Whether a metric's newest point is too old to judge the present with.
   *
   * <p>An agent that stopped reporting leaves its last sample in place. Without
   * this guard every evaluation pass would re-observe that same value, and a
   * sample recorded above the threshold while the agent was alive would keep
   * producing alerts long after the metric stopped existing.
   *
   * @param point newest stored point
   * @param now evaluation time
   * @return true when the point is older than the configured maximum age
   */
  public boolean isStale(MetricStore.MetricPoint point, Instant now) {
    int maxAge = properties.maxPointAgeSeconds();
    if (maxAge <= 0) {
      return false;
    }
    return Duration.between(point.ts(), now).getSeconds() > maxAge;
  }

  /**
   * Resolve the bound a rule compares against.
   *
   * @param rule rule to resolve
   * @return the bound, or empty when a statistical bound cannot be computed yet
   */
  public Optional<AlertStateMachine.Bound> resolveBound(AlertRule rule) {
    if (!rule.usesSigmaBound()) {
      return Optional.of(AlertStateMachine.Bound.absolute(rule.threshold()));
    }
    return metrics.baseline(rule.metricName(), Duration.ofSeconds(rule.windowSeconds()))
        .map(baseline -> AlertStateMachine.Bound.statistical(
            baseline.mean() + rule.sigmaMultiplier() * baseline.stddev()));
  }

  private void deliver(AlertEvent alertEvent) {
    if (alerts.isQuietNow(alertEvent.at())) {
      log.info("notification suppressed by quiet window: rule={} kind={}",
          alertEvent.ruleId(), alertEvent.kind());
      return;
    }
    notifier.dispatch(alertEvent);
  }

  /**
   * Evaluate a single rule immediately, regardless of the schedule. Used by the
   * smoke test and by the "check now" action in the dashboard.
   *
   * <p>Notification delivery runs here too, through the same {@link #deliver}
   * the scheduled pass uses. An earlier version recorded the event without
   * dispatching it, which made an alert raised by this method silently differ
   * from the same alert raised by the scheduler: the history grew and no mail
   * was sent. A domain event must have one behaviour, not one per caller.
   *
   * @param ruleId rule to evaluate
   * @param now evaluation time
   * @return the recorded event, when the evaluation produced one
   */
  @Transactional
  public Optional<AlertEvent> evaluateRuleNow(long ruleId, Instant now) {
    return alerts.enabledRules().stream()
        .filter(rule -> rule.id() == ruleId)
        .findFirst()
        .flatMap(rule -> {
          Optional<MetricStore.MetricPoint> latest = metrics.latest(rule.metricName());
          if (latest.isEmpty() || isStale(latest.get(), now)) {
            return Optional.empty();
          }
          Optional<AlertStateMachine.Bound> bound = resolveBound(rule);
          if (bound.isEmpty()) {
            return Optional.empty();
          }
          AlertState state = alerts.loadState(rule.id());
          AlertEvaluation evaluation =
              machine.evaluate(rule, state, bound.get(), latest.get().value(), now);
          alerts.saveState(evaluation.state());
          evaluation.event().ifPresent(event -> {
            alerts.recordEvent(event);
            deliver(event);
          });
          return evaluation.event();
        });
  }
}
