package com.sfbank.bayanati.operator.domain;

import java.util.Objects;

/**
 * Who is calling an operator endpoint, and at what access level — the explicit input CLAUDE.md's
 * S4-01 task requires every handler to receive rather than invent. AD-002e (back-office
 * authentication) is the open architecture decision that will decide how a real value reaches here;
 * until it closes, nothing in this codebase constructs one from an HTTP request except tests. See
 * {@code operator.web.OperatorIdentityArgumentResolver}'s Javadoc for exactly where that seam is.
 *
 * @param operatorId the operator's own identifier — never a customer- or client-supplied value.
 *     This is what {@code app.profile_status_history.actor_id} and the {@code operator} audit
 *     chain's {@code subject_id} both use, so its shape (username, employee id, whatever AD-002e
 *     settles on) becomes part of two persisted contracts once real values start flowing.
 * @param actorRole the caller's {@code app.operator_role.code} — {@code viewer}, {@code operator}
 *     or {@code admin} — carried for the audit trail alone, never for an authorization decision.
 *     Authorization reads {@link #accessLevel()}; two distinct roles deliberately collapse onto
 *     {@code OPERATOR} there, and this field is what keeps them distinguishable in the record. A
 *     plain {@code String} rather than {@code auth.domain.OperatorRole}, so that {@code
 *     operator.domain} does not take a dependency on the {@code auth} feature, and because the
 *     string is exactly what lands in the payload JSON
 */
public record OperatorIdentity(
    String operatorId, OperatorAccessLevel accessLevel, String actorRole) {

  public OperatorIdentity {
    Objects.requireNonNull(operatorId, "operatorId");
    if (operatorId.isBlank()) {
      throw new IllegalArgumentException("operatorId must not be blank");
    }
    Objects.requireNonNull(accessLevel, "accessLevel");
    Objects.requireNonNull(actorRole, "actorRole");
    if (actorRole.isBlank()) {
      throw new IllegalArgumentException("actorRole must not be blank");
    }
  }
}
