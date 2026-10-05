package com.sfbank.bayanati.auth.domain;

/**
 * The three rows {@code app.operator_role} seeds (V0057). Distinct from {@link
 * com.sfbank.bayanati.operator.domain.OperatorAccessLevel}, which stays two-valued
 * (viewer/operator): this enum is the ROLE a person holds, that one is the access level a request
 * carries.
 *
 * <p>AD-013 (2026-09-13, built at BL-139) makes this a hierarchy with admin on top — viewer views,
 * operator views/prints/approves/rejects/manually completes, and admin holds every operator power
 * plus sole authority to create and manage back-office users. So {@code
 * auth.web.OperatorIdentityFilter} maps both {@code OPERATOR} and {@code ADMIN} onto {@code
 * OperatorAccessLevel.OPERATOR}, and {@code auth.service.OperatorUserDetails} grants an admin
 * {@code ROLE_OPERATOR} and {@code ROLE_VIEWER} alongside {@code ROLE_ADMIN}.
 *
 * <p>This SUPERSEDES AD-002e §3.5(e), under which an admin received no {@code OperatorIdentity} at
 * all and was refused every {@code /api/v1/operator/**} endpoint. AD-002e's own row is left
 * unedited on purpose, as the readable record of what was true until 2026-09-13.
 *
 * <p>Enum constant names lower-case to exactly the {@code app.operator_role.code} values this enum
 * maps to/from — {@link #name()}/{@link #valueOf(String)} with {@link java.util.Locale#ROOT}-cased
 * conversion is the whole mapping, deliberately not a separate lookup table in Java duplicating the
 * database one.
 */
public enum OperatorRole {
  VIEWER,
  OPERATOR,
  ADMIN
}
