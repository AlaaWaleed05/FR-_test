package com.sfbank.bayanati.operator.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamps {@code "actorRole"} onto an operator-chain audit payload.
 *
 * <p><strong>Why the field exists.</strong> Every event this feature writes carries {@code
 * actor_kind='operator'} and an {@code actor_id} UUID. Since AD-013 (BL-139) an {@code admin} holds
 * every operator power, so an admin's approve and an operator's approve were about to become
 * byte-identical in the chain. R-054 records that separation of duties no longer exists in the back
 * office and that the audit trail is the SOLE compensating control — a control that could not
 * answer "was this approved by the person who can also create accounts?" would be weaker than R-054
 * claims it is. Joining back to {@code app.operator_user.role} is not an answer: that column is
 * mutable, so it testifies about now, not about the moment of the action. The payload is
 * append-only and hash-chained, so it does.
 *
 * <p>This is AD-002e's own recorded design, implemented rather than invented — {@code
 * docs/components/persistence.md} already specified "admin actions recorded as {@code
 * actor_kind='operator'} with {@code "actorRole":"admin"} in the payload rather than altering a
 * CHECK on an append-only table". No {@code audit.audit_event} CHECK is touched, so R-035's
 * prohibition on DDL against schema {@code audit} is not engaged, and no migration is needed: only
 * new events carry the field and no existing row is re-hashed.
 *
 * <p>Ordering does not matter. {@code CanonicalJson} emits RFC 8785, which sorts keys, so where a
 * caller inserts this is invisible in the hashed text.
 */
public final class OperatorAuditPayload {

  /**
   * The payload key. One spelling, in one place — it is part of a persisted, unamendable record.
   */
  public static final String ACTOR_ROLE_KEY = "actorRole";

  private OperatorAuditPayload() {}

  /**
   * Returns a copy of {@code payload} with the caller's role stamped on it.
   *
   * @throws IllegalArgumentException if {@code payload} already carries an {@code actorRole}. A
   *     caller supplying its own is confused about who owns the field, and quietly preferring one
   *     of the two values would write a claim nobody chose into a record that can never be amended.
   */
  public static Map<String, Object> withActorRole(
      OperatorIdentity identity, Map<String, Object> payload) {
    if (payload.containsKey(ACTOR_ROLE_KEY)) {
      throw new IllegalArgumentException(
          "payload already carries " + ACTOR_ROLE_KEY + "; it is stamped here, not by the caller");
    }
    // LinkedHashMap, not Map.copyOf: callers legitimately pass null values (an absent
    // internalNote),
    // and Map.copyOf rejects those. A copy, so no caller's map is mutated under it.
    Map<String, Object> stamped = new LinkedHashMap<>(payload);
    stamped.put(ACTOR_ROLE_KEY, identity.actorRole());
    return stamped;
  }
}
