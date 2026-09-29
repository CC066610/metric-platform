package com.example.metrics;

import com.example.metrics.config.MetricPlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MetricPlatformProperties.class)
public class MetricsApplication {

  public static void main(String[] args) {
    SpringApplication.run(MetricsApplication.class, args);
  }
}
