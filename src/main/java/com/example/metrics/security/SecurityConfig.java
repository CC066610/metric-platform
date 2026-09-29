package com.example.metrics.security;

import com.example.metrics.config.MetricPlatformProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Authentication for the two kinds of caller the platform has.
 *
 * <p>An agent pushes metrics; an operator watches the dashboard and manages
 * rules. They get different credentials because they are exposed differently. A
 * key compiled into the dashboard's JavaScript would be readable by anyone who
 * opens devtools, so the browser instead carries a credential the operator types
 * at runtime, and the agent carries a secret that never leaves the host.
 *
 * <p>Both credentials travel in request headers and neither is stored in a
 * cookie, so no request carries authority the caller did not deliberately
 * attach. That is the precondition CSRF protection defends, and its absence is
 * why the filter is disabled here rather than an oversight.
 *
 * <p>The same page throttles failed logins per client address: a static password
 * with no limit is guessable as fast as the network allows, and it is the only
 * thing standing between a caller and the data.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  private static final String DEFAULT_DASHBOARD_USER = "dashboard";

  /** Ten failed logins per quarter hour is well under the usual 100/hour ceiling. */
  private static final int DEFAULT_MAX_AUTH_FAILURES = 10;

  private static final Duration DEFAULT_AUTH_FAILURE_WINDOW = Duration.ofMinutes(15);

  /** Engages only under attack: far above the distinct clients one process sees. */
  private static final int MAX_TRACKED_CLIENTS = 10_000;

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, MetricPlatformProperties properties)
      throws Exception {
    MetricPlatformProperties.Security security = security(properties);

    if (!security.hasApiKey()) {
      log.warn(
          "No API key set (METRICS_API_KEY). Metric ingestion is accepted from loopback "
              + "requests only; every other host will be refused with 401.");
    }

    http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        // A browser preflight carries no credentials by definition, so it has to
        // be answered before the authorization rules below can reject it.
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    // A load balancer must be able to ask whether an instance is
                    // healthy without holding a credential. Everything else the
                    // actuator exposes stays behind the operator login.
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // The dashboard shell has to load before it can ask for a
                    // password; the data it then requests is not public.
                    .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**")
                    .permitAll()
                    // Error renders run after authorization. Leaving /error
                    // protected replaces every real status code with a 401 and
                    // hides the reason a request failed.
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/metrics/batch")
                    .hasRole("AGENT")
                    .anyRequest()
                    .hasRole("OPERATOR"))
        .httpBasic(Customizer.withDefaults())
        // Both are built inline rather than exposed as beans: a Filter bean is
        // also picked up by Boot's servlet filter registration, which would run it
        // a second time outside this chain. The limiter is registered first so it
        // runs first, and a refused client never reaches an authentication attempt.
        .addFilterBefore(
            new AuthFailureLimiterFilter(
                new AuthFailureLimiter(
                    security.maxAuthFailures(),
                    Duration.ofSeconds(security.authFailureWindowSeconds()),
                    MAX_TRACKED_CLIENTS)),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(security.apiKey()),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /**
   * The single operator account, drawn from configuration.
   *
   * <p>A missing password is replaced with a generated one that is logged at
   * startup. The alternative — an implicit well-known default — leaves a reachable
   * deployment protected by a credential that is published in this repository,
   * which is indistinguishable from having no password at all.
   *
   * @param properties deployment settings
   * @param encoder password encoder
   * @return the operator account
   */
  @Bean
  UserDetailsService dashboardUserDetailsService(
      MetricPlatformProperties properties, PasswordEncoder encoder) {
    MetricPlatformProperties.Security security = security(properties);
    String username =
        isBlank(security.dashboardUser()) ? DEFAULT_DASHBOARD_USER : security.dashboardUser();

    String password = security.dashboardPassword();
    if (isBlank(password)) {
      password = generatePassword();
      log.warn(
          """

          ------------------------------------------------------------------
           No dashboard password set, so one was generated for this run.
             user:     {}
             password: {}
           Set DASHBOARD_USER and DASHBOARD_PASSWORD to keep it across restarts.
          ------------------------------------------------------------------""",
          username,
          password);
    }

    return new InMemoryUserDetailsManager(
        User.withUsername(username).password(encoder.encode(password)).roles("OPERATOR").build());
  }

  /**
   * Treats a missing configuration section as blank credentials rather than a
   * failure, so an omitted block still yields a running instance that denies
   * everything instead of one that refuses to start.
   */
  private static MetricPlatformProperties.Security security(MetricPlatformProperties properties) {
    MetricPlatformProperties.Security security = properties.security();
    if (security == null) {
      security = new MetricPlatformProperties.Security("", "", "", 0, 0);
    }
    // An omitted section binds the throttling knobs to zero, and "no failures
    // allowed" would refuse every client including the operator. A missing value
    // has to fall back to the default policy, never to a lockout.
    return new MetricPlatformProperties.Security(
        security.apiKey(),
        security.dashboardUser(),
        security.dashboardPassword(),
        security.maxAuthFailures() > 0 ? security.maxAuthFailures() : DEFAULT_MAX_AUTH_FAILURES,
        security.authFailureWindowSeconds() > 0
            ? security.authFailureWindowSeconds()
            : (int) DEFAULT_AUTH_FAILURE_WINDOW.toSeconds());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String generatePassword() {
    byte[] bytes = new byte[18];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
