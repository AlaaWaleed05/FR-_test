package com.sfbank.bayanati.operator.domain;

/**
 * A {@link OperatorAccessLevel#VIEWER} called an operator-only action (approve, reject) —
 * operator.md's access table: a viewer may search, filter and view, "plus" approve/reject/manually
 * complete/export is {@link OperatorAccessLevel#OPERATOR}-only. An ordinary 403.
 *
 * <p>Only a viewer can raise this. Since AD-013 (BL-139) an admin arrives at the {@code OPERATOR}
 * access level, so an admin never trips these gates — viewer is the sole remaining level below
 * them.
 */
public class AccessLevelRequiredException extends RuntimeException {

  public AccessLevelRequiredException(String message) {
    super(message);
  }
}
