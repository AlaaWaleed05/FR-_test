package com.sfbank.bayanati.requestlimits.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.salarycertificate.web.SalaryCertificateController;
import com.sfbank.bayanati.signature.web.SignatureController;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** S9-08: the boundary size limit, and the guard that stops it drifting past what it protects. */
class RequestBodySizeLimitFilterTest {

  private static final long LIMIT = 1000L;

  @Test
  void aBodyLargerThanTheLimitIsRefusedWithoutReachingTheChain() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/salary-certificate");
    request.setContent(new byte[(int) LIMIT + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new RequestBodySizeLimitFilter(LIMIT).doFilter(request, response, chain);

    assertEquals(413, response.getStatus());
    // The point of the filter is that nothing downstream ever sees the request, so the body is
    // never buffered. MockFilterChain records the request it was called with; a null one is the
    // proof that the chain was not invoked at all.
    assertNull(chain.getRequest(), "an oversized request must not reach the chain");
  }

  @Test
  void aBodyAtTheLimitPasses() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/salary-certificate");
    request.setContent(new byte[(int) LIMIT]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new RequestBodySizeLimitFilter(LIMIT).doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertNotNull(chain.getRequest(), "a request at the limit must reach the chain");
  }

  @Test
  void aRequestDeclaringNoLengthPassesThroughToTheExistingPerEndpointCheck() throws Exception {
    // Chunked transfer encoding declares no Content-Length, and getContentLengthLong() returns -1.
    // This filter deliberately does not try to handle that case -- see its javadoc and BL-170 --
    // and this test pins the behaviour so the limitation is a recorded decision rather than an
    // assumption somebody later has to rediscover.
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/salary-certificate");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new RequestBodySizeLimitFilter(LIMIT).doFilter(request, response, chain);

    assertEquals(-1, request.getContentLengthLong());
    assertEquals(200, response.getStatus());
    assertNotNull(chain.getRequest());
  }

  @Test
  void theBoundaryLimitStaysAboveEveryPerEndpointLimitItProtects() throws Exception {
    // THE DRIFT THIS GUARDS. fru.http.max-request-body-bytes must never fall to or below a
    // controller's own MAX_BASE64_LENGTH. If it did, the filter would start refusing legitimate
    // uploads with a bare 413 before the controller could produce its own, far more useful
    // message -- and the symptom would be a customer whose salary certificate silently fails to
    // upload at a size that the code everywhere else says is allowed.
    //
    // Read from the classpath rather than restating the numbers, because restating them here is
    // the exact drift this test exists to catch.
    Properties properties = new Properties();
    try (InputStream stream =
        RequestBodySizeLimitFilterTest.class.getResourceAsStream("/application.properties")) {
      assertNotNull(stream, "application.properties must be on the test classpath");
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    String configured = properties.getProperty("fru.http.max-request-body-bytes");
    assertNotNull(
        configured,
        "fru.http.max-request-body-bytes has no default -- deleting it fails startup, and this"
            + " assertion is what says so before the container does");

    long boundaryLimit = Long.parseLong(configured.trim());

    assertTrue(
        boundaryLimit > SalaryCertificateController.MAX_BASE64_LENGTH,
        "boundary limit "
            + boundaryLimit
            + " must exceed the salary certificate's own limit of "
            + SalaryCertificateController.MAX_BASE64_LENGTH);
    assertTrue(
        boundaryLimit > SignatureController.MAX_BASE64_LENGTH,
        "boundary limit "
            + boundaryLimit
            + " must exceed the signature's own limit of "
            + SignatureController.MAX_BASE64_LENGTH);
  }
}
