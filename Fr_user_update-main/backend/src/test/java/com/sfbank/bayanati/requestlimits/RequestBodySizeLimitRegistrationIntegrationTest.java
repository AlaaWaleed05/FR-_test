package com.sfbank.bayanati.requestlimits;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S9-08: proves {@link com.sfbank.bayanati.requestlimits.web.RequestBodySizeLimitFilter} is
 * actually registered, actually mapped, and actually carries the configured limit.
 *
 * <p><strong>Why this class exists separately from the filter's unit test.</strong> Those four unit
 * tests construct the filter directly and call {@code doFilter}. They would all still pass if the
 * {@code FilterRegistrationBean} were never registered, if {@code addUrlPatterns} matched nothing,
 * or if the bean were scoped to the wrong paths — the guard would be entirely absent and the suite
 * entirely green. That is CLAUDE.md's "a green test could coexist with a broken guard" case, so the
 * guard needs proving where it runs rather than where it is defined. Found at review.
 *
 * <p>MockMvc is the right instrument here despite not being a real socket: Spring Boot's {@code
 * SpringBootMockMvcBuilderCustomizer} collects {@code FilterRegistrationBean}s from the context and
 * registers them WITH their URL patterns, so a pattern that matches nothing fails these tests.
 *
 * <p>The oversized bodies here are real 17 MB allocations rather than a lowered limit set through
 * {@code @DynamicPropertySource}. A lowered limit would prove the mechanism while leaving the
 * number this system actually ships with untested, and the number is half the point.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodySizeLimitRegistrationIntegrationTest extends AbstractPostgresIntegrationTest {

  /** One byte past {@code fru.http.max-request-body-bytes}. */
  private static final int OVER_LIMIT = 17_000_001;

  @Autowired private MockMvc mockMvc;

  @Test
  void anOversizedBodyToTheSalaryCertificateEndpointIsRefusedWith413() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/salary-certificate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[OVER_LIMIT]))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void anOversizedBodyToTheIdentityScanEndpointIsAlsoRefused() throws Exception {
    // THIS IS THE CASE THE FIRST DRAFT MISSED, and the reason the filter is mapped to a prefix
    // rather than to a list of endpoints. /api/v1/identity-scan/scan-result carries a JWS, not
    // base64 bytes, so it was not on the "endpoints that accept bytes" list -- but its length is
    // checked after Jackson has materialised it, exactly like the base64 endpoints, so a 500 MB
    // post got 500 MB of heap on a path the narrow scope excluded. An endpoint list reproduces
    // this bug every time an endpoint is added and nobody remembers to extend it.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[OVER_LIMIT]))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void anOrdinarySizedBodyIsNotRefusedByThisFilter() throws Exception {
    // The complement, without which the two assertions above would also pass against a filter that
    // rejected everything. A malformed-but-small body reaches the controller and is answered on its
    // merits; what matters is only that the answer is not 413.
    mockMvc
        .perform(
            post("/api/v1/salary-certificate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"not-a-uuid\"}"))
        .andExpect(status().is(not(413)));
  }
}
