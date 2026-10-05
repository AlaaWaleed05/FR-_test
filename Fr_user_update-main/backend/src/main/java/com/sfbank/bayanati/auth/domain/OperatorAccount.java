package com.sfbank.bayanati.auth.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One {@code app.operator_user} row, as read back by {@link OperatorUserRepository}. Carries
 * exactly what CLAUDE.md's S4-05 task says an operator account stores: login name, real name, role,
 * active flag, password hash, must-change flag — nothing else (no email, no lockout columns).
 *
 * @param userId {@code app.operator_user.user_id} — the UUID that is, and must remain, the ONLY
 *     identifier {@link com.sfbank.bayanati.operator.domain.OperatorIdentity#operatorId()} ever
 *     carries. See AD-002e R1. Its original justification was the four-eyes predicate (V0009),
 *     which AD-013 removed on 2026-09-13; the conclusion is unchanged and now rests on the audit
 *     trail instead — {@code actor_id} is written into append-only {@code
 *     app.profile_status_history} and {@code audit.audit_event} rows, so a renameable identifier
 *     would silently re-attribute past actions with no correction path.
 */
public record OperatorAccount(
    UUID userId,
    String username,
    String displayName,
    OperatorRole role,
    String passwordHash,
    boolean mustChangePassword,
    boolean isEnabled) {

  public OperatorAccount {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(passwordHash, "passwordHash");
  }
}
