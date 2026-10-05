package com.sfbank.bayanati.auth.web;

import com.sfbank.bayanati.auth.domain.IncorrectCurrentPasswordException;
import com.sfbank.bayanati.auth.domain.PasswordTooLongException;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import com.sfbank.bayanati.auth.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/v1/auth/password} — the ONLY endpoint a {@code must_change_password} account may
 * reach (AD-002e §3.4, enforced by {@code auth.config.SecurityConfiguration}'s authorization rules,
 * not by anything in this class).
 *
 * <p><strong>The thing that is always forgotten</strong> (AD-002e research report §3.4): after the
 * password changes, the session's {@code Authentication} still carries the restricted {@code
 * ROLE_PASSWORD_CHANGE_REQUIRED} authority until rebuilt and explicitly saved — {@code
 * SecurityContextHolderFilter} only READS the context since Spring Security 6's {@code
 * requireExplicitSave} default, it never auto-saves a context mutated mid-request.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordChangeController {

  private final PasswordChangeService passwordChangeService;
  private final UserDetailsService userDetailsService;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();
  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  public PasswordChangeController(
      PasswordChangeService passwordChangeService, UserDetailsService userDetailsService) {
    this.passwordChangeService = passwordChangeService;
    this.userDetailsService = userDetailsService;
  }

  @PostMapping("/password")
  public ResponseEntity<Void> changePassword(
      HttpServletRequest request,
      HttpServletResponse response,
      @AuthenticationPrincipal OperatorUserDetails principal,
      @RequestBody PasswordChangeRequest body) {
    try {
      passwordChangeService.changePassword(
          principal.userId(), principal.getPassword(), body.currentPassword(), body.newPassword());
    } catch (IncorrectCurrentPasswordException incorrect) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, incorrect.getMessage());
    } catch (PasswordTooLongException tooLong) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, tooLong.getMessage());
    }

    // Reload: must_change_password is now false, so the fresh authorities are the full
    // viewer/operator/admin set, not ROLE_PASSWORD_CHANGE_REQUIRED.
    OperatorUserDetails refreshed =
        (OperatorUserDetails) userDetailsService.loadUserByUsername(principal.getUsername());
    Authentication reauthenticated =
        UsernamePasswordAuthenticationToken.authenticated(
            refreshed, null, refreshed.getAuthorities());
    SecurityContext context = securityContextHolderStrategy.createEmptyContext();
    context.setAuthentication(reauthenticated);
    securityContextHolderStrategy.setContext(context);
    securityContextRepository.saveContext(context, request, response);
    request.changeSessionId();

    return ResponseEntity.noContent().build();
  }
}
