package com.sfbank.bayanati.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;

/**
 * BL-066. The operator's CSRF cookie must carry {@code Secure} where the deployment terminates TLS.
 *
 * <p>WHY THIS TEST READS THE HEADER RATHER THAN THE CONFIGURATION. The defect it guards was live on
 * AWS and invisible to the entire existing suite, because nothing anywhere asserted what was
 * actually written to {@code Set-Cookie}. It was found by reading the header off a real response
 * through CloudFront:
 *
 * <pre>
 *   Set-Cookie: XSRF-TOKEN=20e70cb7-...; Path=/
 *   Set-Cookie: JSESSIONID=0EDB1A54...; Path=/; HttpOnly
 * </pre>
 *
 * <p>Neither carried {@code Secure}, because Boot derived "is this secure?" from {@code
 * X-Forwarded-Proto}, which the ALB sets from the CloudFront-to-ALB hop — HTTP — rather than from
 * the viewer's HTTPS connection two hops earlier. A test asserting "the property is set" would have
 * passed against the broken build. This one cannot.
 *
 * <p>The negative case is not filler. A {@code Secure} cookie is silently dropped by the browser
 * over plain HTTP, so switching this on unconditionally would break local development in the least
 * debuggable way available — sign-in succeeds, the cookie never arrives, the next request is
 * anonymous. The default must stay off, and that is worth a test of its own.
 */
class SecurityConfigurationCsrfCookieTest {

  private static String setCookieHeader(boolean requireSecure) {
    CookieCsrfTokenRepository repository = SecurityConfiguration.csrfTokenRepository(requireSecure);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    CsrfToken token = repository.generateToken(request);
    repository.saveToken(token, request, response);
    return response.getHeader("Set-Cookie");
  }

  @Test
  @DisplayName("carries Secure when the deployment guarantees TLS")
  void carriesSecureWhenRequired() {
    String header = setCookieHeader(true);

    assertThat(header).isNotNull();
    assertThat(header).contains("XSRF-TOKEN=");
    assertThat(header).contains("Secure");
  }

  @Test
  @DisplayName("omits Secure by default, so local HTTP development is not silently broken")
  void omitsSecureByDefault() {
    String header = setCookieHeader(false);

    assertThat(header).isNotNull();
    assertThat(header).contains("XSRF-TOKEN=");
    assertThat(header).doesNotContain("Secure");
  }

  @Test
  @DisplayName("stays readable by JavaScript in both states — HttpOnly would break the SPA")
  void neverHttpOnly() {
    // withHttpOnlyFalse() is load-bearing: the back office reads this cookie from JavaScript and
    // echoes it back as a header. Adding Secure must not quietly change that.
    assertThat(setCookieHeader(true)).doesNotContain("HttpOnly");
    assertThat(setCookieHeader(false)).doesNotContain("HttpOnly");
  }
}
