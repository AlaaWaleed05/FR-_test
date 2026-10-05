package com.sfbank.bayanati.auth.web;

import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import java.util.Locale;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/auth/me} — lets a signed-in SPA (and this session's own proof tests) learn its
 * role and must-change state directly, rather than discovering it from a string of 403s. Refused
 * (403) for a {@code must_change_password} account by {@code auth.config.SecurityConfiguration}'s
 * authorization rule, not a controller check.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class MeController {

  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal OperatorUserDetails principal) {
    return new MeResponse(
        principal.getUsername(),
        principal.displayName(),
        principal.role().name().toLowerCase(Locale.ROOT),
        principal.mustChangePassword());
  }
}
