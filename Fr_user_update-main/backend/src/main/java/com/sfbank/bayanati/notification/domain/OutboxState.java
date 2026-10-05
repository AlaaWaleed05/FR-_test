package com.sfbank.bayanati.notification.domain;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;

/**
 * The three states {@code app.notification_outbox.state} (V0035) may hold, and the retry policy
 * that decides which one a dispatch result moves a row to.
 *
 * <p>This is a chosen behaviour — CLAUDE.md reserves {@code domain}/{@code service} for exactly
 * that, so it lives here rather than inside the JDBC repository that merely applies it: {@code
 * ACCEPTED} is terminal ({@link #DISPATCHED}), {@code REJECTED} and {@code PERMANENT_FAILURE} are
 * terminal and never retried ({@link #FAILED} — the destination or the account is the problem, not
 * a transient condition), and {@code TRANSIENT_FAILURE} stays {@link #PENDING} so the poll query
 * (AD-005 §6) picks it up again once its lease (the claim-time {@code next_attempt_at} advance)
 * expires.
 */
public enum OutboxState {
  PENDING("pending"),
  DISPATCHED("dispatched"),
  FAILED("failed");

  private final String wireValue;

  OutboxState(String wireValue) {
    this.wireValue = wireValue;
  }

  /**
   * Must stay byte-identical to the {@code CHECK} constraint on {@code
   * app.notification_outbox.state}.
   */
  public String wireValue() {
    return wireValue;
  }

  public static OutboxState forOutcome(DispatchOutcome outcome) {
    return switch (outcome) {
      case ACCEPTED -> DISPATCHED;
      case REJECTED, PERMANENT_FAILURE -> FAILED;
      case TRANSIENT_FAILURE -> PENDING;
    };
  }
}
