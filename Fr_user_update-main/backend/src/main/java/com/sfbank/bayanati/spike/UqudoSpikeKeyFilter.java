package com.sfbank.bayanati.spike;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /api/v1/spike/**} sits in the permitAll customer chain, so anyone on the LAN could
 * otherwise mint tenant tokens or purge sessions. One shared secret, sent as {@code X-Spike-Key},
 * closes that for the duration of the spike. Not a security design -- a throwaway gate.
 */
@Component
@Profile("uqudo-spike")
class UqudoSpikeKeyFilter extends OncePerRequestFilter {

  private final UqudoSpikeProperties properties;

  UqudoSpikeKeyFilter(UqudoSpikeProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/spike/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Spike-Key");
    String expected = properties.spikeKey();
    if (supplied == null
        || expected == null
        || expected.isBlank()
        || !MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "spike key");
      return;
    }
    chain.doFilter(request, response);
  }
}
