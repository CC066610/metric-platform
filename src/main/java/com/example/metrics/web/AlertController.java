package com.example.metrics.web;

import com.example.metrics.domain.AlertEvent;
import com.example.metrics.service.AlertEvaluator;
import com.example.metrics.store.AlertStore;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rule management plus alert state and history for the dashboard. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

  private final AlertStore alerts;
  private final AlertEvaluator evaluator;

  /**
   * Create the controller.
   *
   * @param alerts alert persistence
   * @param evaluator evaluation service, used by the manual trigger
   */
  public AlertController(AlertStore alerts, AlertEvaluator evaluator) {
    this.alerts = alerts;
    this.evaluator = evaluator;
  }

  /**
   * Current state of every rule.
   *
   * @return rule status rows
   */
  @GetMapping("/status")
  public List<AlertStore.RuleStatusView> status() {
    return alerts.ruleStatuses();
  }

  /**
   * Recent alert history.
   *
   * @param limit maximum entries to return
   * @return history, newest first
   */
  @GetMapping("/events")
  public List<AlertEvent> events(@RequestParam(defaultValue = "50") int limit) {
    return alerts.recentEvents(Math.max(1, Math.min(limit, 500)));
  }

  /**
   * Create a rule.
   *
   * @param request validated rule definition
   * @return the new rule identity
   */
  @PostMapping("/rules")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Long> create(@Valid @RequestBody AlertRuleRequest request) {
    long id = alerts.createRule(
        request.metricName(),
        request.operator(),
        request.threshold(),
        request.sigmaMultiplier(),
        request.windowSeconds(),
        request.minConsecutive(),
        request.cooldownSeconds());
    return Map.of("id", id);
  }

  /**
   * Delete a rule and its state and history.
   *
   * @param id rule identity
   * @return the number of deleted rules
   */
  @DeleteMapping("/rules/{id}")
  public Map<String, Integer> delete(@PathVariable long id) {
    return Map.of("deleted", alerts.deleteRule(id));
  }

  /**
   * Enable or disable a rule without deleting it.
   *
   * @param id rule identity
   * @param enabled new value
   * @return the number of updated rules
   */
  @PostMapping("/rules/{id}/enabled")
  public Map<String, Integer> setEnabled(
      @PathVariable long id, @RequestParam boolean enabled) {
    return Map.of("updated", alerts.setEnabled(id, enabled));
  }

  /**
   * Evaluate one rule immediately instead of waiting for the next scheduled pass.
   *
   * @param id rule identity
   * @return the recorded event, or null when the evaluation produced none
   */
  @PostMapping("/rules/{id}/evaluate")
  public AlertEvent evaluate(@PathVariable long id) {
    return evaluator.evaluateRuleNow(id, Instant.now()).orElse(null);
  }
}
