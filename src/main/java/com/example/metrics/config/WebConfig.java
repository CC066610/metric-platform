package com.example.metrics.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the dashboard dev server to call the API during development. The
 * allowed origins come from configuration so a deployment can narrow or drop
 * this without a code change.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final MetricPlatformProperties properties;

  /**
   * Create the web configuration.
   *
   * @param properties deployment settings
   */
  public WebConfig(MetricPlatformProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String origins = properties.cors().allowedOrigins();
    if (origins == null || origins.isBlank()) {
      return;
    }
    registry.addMapping("/api/**")
        .allowedOrigins(origins.split("\\s*,\\s*"))
        .allowedMethods("GET", "POST", "DELETE")
        .maxAge(3600);
  }
}
