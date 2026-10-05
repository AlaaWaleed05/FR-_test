package com.sfbank.bayanati.operator.domain;

/**
 * The two access levels a request can carry. A {@link #VIEWER} can search, filter and view
 * everything a profile holds, including images; only an {@link #OPERATOR} may approve, reject,
 * manually complete or export.
 *
 * <p><strong>Still two-valued after AD-013 (BL-139), and deliberately so.</strong> AD-013 made the
 * ROLE ladder a hierarchy with admin on top, but admin maps ONTO {@link #OPERATOR} rather than
 * becoming a third level here — an admin may do everything an operator may, and that is exactly
 * what {@link #OPERATOR} already means. What admin holds beyond it is the {@code /api/v1/admin/**}
 * surface, which is a Spring Security rule keyed on {@code ROLE_ADMIN}, not an access level. Adding
 * a third constant would force every {@code != OPERATOR} gate in the service tier to be rewritten
 * as a set membership test for no gain. See {@code auth.domain.OperatorRole} for the role enum this
 * is derived from, and {@code auth.web.OperatorIdentityFilter#accessLevelOf} for the one mapping.
 */
public enum OperatorAccessLevel {
  VIEWER,
  OPERATOR
}
