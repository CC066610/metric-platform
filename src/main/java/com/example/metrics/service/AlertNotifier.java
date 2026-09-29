package com.example.metrics.service;

import com.example.metrics.config.MetricPlatformProperties;
import com.example.metrics.domain.AlertEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSender;
import org.springframework.stereotype.Service;

/**
 * Delivers alert notifications off the evaluation path.
 *
 * <p>Delivery runs on a single background thread: an SMTP round trip must never
 * delay metric ingestion or the next evaluation pass. Failures are logged and
 * dropped rather than retried, because a retry queue would need its own
 * durability story and this platform's alert volume does not justify one.
 */
@Service
public class AlertNotifier {

  private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

  private final ObjectProvider<MailSender> mailSender;
  private final MetricPlatformProperties properties;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "alert-notifier");
        thread.setDaemon(true);
        return thread;
      });

  /**
   * Create the notifier.
   *
   * @param mailSender mail transport, resolved lazily so a missing mail configuration
   *     does not prevent startup
   * @param properties deployment settings, including the recipient
   */
  public AlertNotifier(ObjectProvider<MailSender> mailSender, MetricPlatformProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /**
   * Queue a notification for delivery.
   *
   * @param event alert history entry to announce
   */
  public void dispatch(AlertEvent event) {
    String recipient = properties.alerts().notifyTo();
    if (recipient == null || recipient.isBlank()) {
      log.info("alert (mail disabled) {}", event.message());
      return;
    }
    executor.submit(() -> send(event, recipient));
  }

  private void send(AlertEvent event, String recipient) {
    MailSender sender = mailSender.getIfAvailable();
    if (sender == null) {
      log.warn("no mail transport configured; alert not delivered: {}", event.message());
      return;
    }
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(properties.alerts().notifyFrom());
      message.setTo(recipient.split("\\s*,\\s*"));
      message.setSubject("[%s] %s".formatted(event.kind(), event.metricName()));
      message.setText(event.message() + "\n\nat " + event.at());
      sender.send(message);
      log.info("alert delivered to {}", recipient);
    } catch (RuntimeException failure) {
      // Deliberately non-fatal: a broken mail server must not stop evaluation.
      log.error("alert delivery failed for rule {}", event.ruleId(), failure);
    }
  }

  /** Stop the notifier thread when the application context closes. */
  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}
