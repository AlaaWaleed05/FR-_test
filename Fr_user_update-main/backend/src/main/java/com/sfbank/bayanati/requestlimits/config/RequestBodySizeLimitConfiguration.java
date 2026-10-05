package com.sfbank.bayanati.requestlimits.config;

import com.sfbank.bayanati.requestlimits.web.RequestBodySizeLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestBodySizeLimitFilter} on the two endpoints that accept bytes.
 *
 * <p>Registered as a plain servlet filter rather than through the Spring Security chain, and at
 * {@link Ordered#HIGHEST_PRECEDENCE}, deliberately: the whole value of this limit is that it acts
 * before anything else looks at the request. A filter placed inside the security chain would run
 * after session resolution and CSRF, both of which are cheap but neither of which should be asked
 * to happen on behalf of a body we are about to refuse.
 *
 * <p>{@code fru.http.max-request-body-bytes} has no default, matching how this file's other
 * deployment-shaping properties behave: a missing value fails startup rather than silently
 * restoring the framework's absence of a limit, which is the exact condition S9-08 existed to end.
 */
@Configuration
public class RequestBodySizeLimitConfiguration {

  /**
   * The whole customer and operator API, not a list of the endpoints that happen to carry the most
   * bytes.
   *
   * <p>An earlier draft of this class scoped the filter to {@code /api/v1/signature} and {@code
   * /api/v1/salary-certificate} on the reasoning that they were "the two endpoints that accept
   * bytes". That was wrong, and reviewing it is what found the hole: {@code
   * /api/v1/identity-scan/scan-result} and {@code /api/v1/liveness/result} accept a JWS whose
   * length is capped at {@code IdentityScanController.MAX_JWS_LENGTH} — but, exactly like the
   * base64 endpoints, only AFTER Jackson has materialised it. A 500 MB post to either got 500 MB of
   * heap, which is the precise problem this filter exists to prevent, on a path the narrow scope
   * excluded.
   *
   * <p>Listing endpoints therefore reproduced the bug it was meant to fix, because the list has to
   * be re-derived every time an endpoint is added and nothing fails when it is not. A prefix needs
   * no maintenance and cannot be forgotten. It costs nothing on the small-JSON endpoints: a request
   * under the limit is one {@code long} comparison, and a GET with no body declares no length.
   */
  static final String[] GUARDED_PATHS = {"/api/v1/*"};

  @Bean
  public FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilter(
      @Value("${fru.http.max-request-body-bytes}") long maxBodyBytes) {

    FilterRegistrationBean<RequestBodySizeLimitFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(new RequestBodySizeLimitFilter(maxBodyBytes));
    registration.addUrlPatterns(GUARDED_PATHS);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
