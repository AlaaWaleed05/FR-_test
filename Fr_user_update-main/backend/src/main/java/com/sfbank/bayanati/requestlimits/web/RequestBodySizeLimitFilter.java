package com.sfbank.bayanati.requestlimits.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request whose declared body is larger than this API ever legitimately sends, before
 * anything reads it.
 *
 * <p><strong>Why this exists (S9-08).</strong> There is no multipart endpoint in this application.
 * The signature and the salary certificate arrive as base64 inside a JSON body; identity and
 * liveness results arrive as a JWS in one. Spring Boot applies no default size limit to a JSON body
 * — {@code spring.servlet.multipart.*} governs multipart only, and Tomcat's {@code maxPostSize}
 * governs form content only — so until this filter, the FIRST thing that looked at the size of any
 * of them was the controller's own length check, which runs after Jackson has already materialised
 * the whole string on the heap. A caller sending a 500 MB body got 500 MB of heap allocated on its
 * behalf and was then told the request was too large.
 *
 * <p>That is a capacity limit, not only a robustness one, and it is why {@code
 * server.tomcat.threads.max} is set to 100 rather than the inherited 200: the worst-case heap for
 * one in-flight upload (~16 MB of base64 {@code String} plus the ~12 MB {@code byte[]} that {@code
 * Base64.getDecoder().decode} allocates beside it) multiplied by the worker count is what has to
 * fit in the ~3 GB heap §4.1 of the hosting specification sizes. A cap on one request is what makes
 * that multiplication meaningful.
 *
 * <p><strong>What this closes, and what it does not.</strong> It rejects on {@code Content-Length},
 * which every real client of this API sets — the Flutter app's HTTP client sets it for any string
 * or byte body. A request using chunked transfer encoding declares no length, and this filter lets
 * it through to the existing per-endpoint check, exactly as before. So this is a capacity guard
 * against ordinary clients and buggy ones; it is NOT a complete defence against a caller
 * deliberately choosing chunked encoding to avoid it. Closing that needs a counting wrapper around
 * the request's input stream, which is tracked as BL-170 rather than written here, because the
 * wrapper has to reimplement {@code ServletInputStream}'s async contract and deserves its own tests
 * rather than a corner of this one.
 *
 * <p><strong>The caller usually sees a connection reset, not the 413.</strong> This filter does not
 * drain the body it refuses, and Tomcat's {@code maxSwallowSize} defaults to 2 MB — so for a
 * request that trips this limit, which is by definition larger than that, Tomcat aborts the
 * connection rather than reading the remainder to keep it alive. The response is well-formed and
 * the container does not continue processing; the client simply may not get to read it. That is
 * accepted rather than worked around, because the alternative is reading 17 MB or more off the wire
 * in order to deliver a courtesy status code, which would give back the heap and time this filter
 * exists to save. The status is still correct for the clients that do see it.
 *
 * <p>Scoped to the whole {@code /api/v1/*} prefix rather than to a list of endpoints — see {@code
 * RequestBodySizeLimitConfiguration.GUARDED_PATHS} for why an endpoint list reproduced the bug it
 * was meant to fix.
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

  private final long maxBodyBytes;

  public RequestBodySizeLimitFilter(long maxBodyBytes) {
    this.maxBodyBytes = maxBodyBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    long declared = request.getContentLengthLong();
    if (declared > maxBodyBytes) {
      // 413 rather than 400: the request is well-formed and the client can act on the distinction.
      // No body, and nothing about the request is logged here — a rejected upload's size is not
      // interesting and its content must never be.
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
      return;
    }
    chain.doFilter(request, response);
  }
}
