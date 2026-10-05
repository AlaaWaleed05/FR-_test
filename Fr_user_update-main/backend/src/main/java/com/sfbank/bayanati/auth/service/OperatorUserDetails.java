package com.sfbank.bayanati.auth.service;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The {@link UserDetails} principal for one {@code app.operator_user} row.
 *
 * <p><strong>Authority mapping is the single most important correctness point in this whole
 * feature</strong> (AD-002e §3.4): a {@code must_change_password = true} account is granted ONLY
 * {@code ROLE_PASSWORD_CHANGE_REQUIRED} — none of {@code VIEWER}/{@code OPERATOR}/{@code ADMIN} —
 * so {@code AuthorizationFilter} denies every rule but {@code POST /api/v1/auth/password} before
 * any controller method runs. Otherwise: {@code viewer} → {@code ROLE_VIEWER}; {@code operator} →
 * {@code ROLE_OPERATOR} AND {@code ROLE_VIEWER} (the DUAL grant — {@code
 * auth.config.SecurityConfiguration}'s coarse gate on {@code /api/v1/operator/**} is {@code
 * hasRole("VIEWER")}, so an operator granted only {@code ROLE_OPERATOR} would 403 before ever
 * reaching a controller); {@code admin} → {@code ROLE_ADMIN} AND {@code ROLE_OPERATOR} AND {@code
 * ROLE_VIEWER}, the ladder AD-013 (2026-09-13, BL-139) settled. Until then admin was granted {@code
 * ROLE_ADMIN} alone, which is what made {@code /api/v1/operator/**} 403 for an authenticated admin
 * (AD-002e §3.5(e), now superseded).
 *
 * <p>{@link #isEnabled()} reflects {@code is_enabled} directly, so a disabled account is refused at
 * sign-in time by {@code DaoAuthenticationProvider}'s own pre-authentication check — independent
 * of, and in addition to, {@code auth.web.OperatorIdentityFilter}'s per-request re-read, which is
 * what closes the live-session case (a session opened before the account was disabled).
 */
public final class OperatorUserDetails implements UserDetails {

  private final OperatorAccount account;

  public OperatorUserDetails(OperatorAccount account) {
    this.account = account;
  }

  public UUID userId() {
    return account.userId();
  }

  public OperatorRole role() {
    return account.role();
  }

  public boolean mustChangePassword() {
    return account.mustChangePassword();
  }

  public String displayName() {
    return account.displayName();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    if (account.mustChangePassword()) {
      return List.of(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE_REQUIRED"));
    }
    return switch (account.role()) {
      case VIEWER -> List.of(new SimpleGrantedAuthority("ROLE_VIEWER"));
      case OPERATOR ->
          List.of(
              new SimpleGrantedAuthority("ROLE_OPERATOR"),
              new SimpleGrantedAuthority("ROLE_VIEWER"));
      // AD-013 (2026-09-13, BL-139): the ladder is a hierarchy and admin is its top rung, so admin
      // carries everything OPERATOR carries and ROLE_ADMIN on top. Both of the inherited
      // authorities are load-bearing: ROLE_VIEWER clears the coarse gate on the
      // /api/v1/operator/** prefix, ROLE_OPERATOR clears the two more specific rules ahead of it,
      // covering four endpoints (approve, reject, manual-complete, export). Dropping either
      // silently removes an AD-013
      // power, and no SecurityConfiguration rule needs to know admin exists.
      case ADMIN ->
          List.of(
              new SimpleGrantedAuthority("ROLE_ADMIN"),
              new SimpleGrantedAuthority("ROLE_OPERATOR"),
              new SimpleGrantedAuthority("ROLE_VIEWER"));
    };
  }

  @Override
  public String getPassword() {
    return account.passwordHash();
  }

  @Override
  public String getUsername() {
    return account.username();
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    // Deliberately not modelled as false for must_change_password — see AD-002e §3.4: that would
    // fail authentication outright via a post-authentication check, leaving no session in which
    // to change anything. The restricted-authority approach above is the whole mechanism.
    return true;
  }

  @Override
  public boolean isEnabled() {
    return account.isEnabled();
  }
}
