package com.sfbank.bayanati.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port onto {@code app.operator_user} (V0057). The one adapter is {@code auth.jdbc}. */
public interface OperatorUserRepository {

  /**
   * Unfiltered by {@code is_enabled} — used both by {@code auth.service.OperatorUserDetailsService}
   * (which must see a disabled row to correctly deny it via {@code UserDetails.isEnabled() ==
   * false}, not silently report "no such user") and by {@code auth.web.AuthFailureAuditListener}
   * (which needs to know an account exists even if it is disabled, to fill {@code accountExists}
   * honestly).
   */
  Optional<OperatorAccount> findByUsername(String username);

  /**
   * Filtered to {@code is_enabled = true}. The one method {@code auth.web.OperatorIdentityFilter}
   * calls on every request to a chain-1 endpoint — an empty result (disabled since login) is what
   * makes account disabling take effect on a live session rather than only at next login.
   */
  Optional<OperatorAccount> findActiveById(UUID userId);

  /**
   * Inserts one row with {@code must_change_password = true}. Used only by {@code
   * auth.config.CreateOperatorAccountRunner} (S4-05) — no HTTP path creates an account this session
   * (see BACKLOG.md).
   *
   * @param createdBy the acting admin's {@code user_id}, or {@code null} for a CLI-created account
   *     with no admin session behind it
   * @return the new row's {@code user_id}
   * @throws DuplicateUsernameException if {@code username} is already taken
   */
  UUID create(
      String username, String displayName, OperatorRole role, String passwordHash, UUID createdBy);

  /** Sets a new hash, clears {@code must_change_password}, stamps {@code password_changed_at}. */
  void updatePassword(UUID userId, String passwordHash, Instant changedAt);

  /** Stamps {@code last_sign_in_at}. Best-effort bookkeeping, not itself an audit record. */
  void recordSignIn(UUID userId, Instant at);

  /**
   * {@code SELECT audit.ensure_operator_chain(:userId)} (V0047) — idempotent, safe to call before
   * every operator-attributed audit write, not only the first. Mirrors {@code
   * operator.domain.ProfileListRepository#ensureOperatorChain}'s exact pattern.
   */
  void ensureOperatorChain(UUID userId);
}
