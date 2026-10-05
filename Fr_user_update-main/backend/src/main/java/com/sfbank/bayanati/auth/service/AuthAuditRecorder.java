package com.sfbank.bayanati.auth.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place every authentication-related audit event is written, so the two decided payload
 * shapes (research §3.5(h)/(i), CLAUDE.md task §2) are enforced in exactly one class rather than
 * re-derived at every call site:
 *
 * <ul>
 *   <li>A KNOWN identity (sign-in success, sign-out, password change) audits to that operator's OWN
 *       chain via {@code audit.ensure_operator_chain} (V0047) — {@code actor_kind='operator'},
 *       {@code actor_id} = the UUID, never the username.
 *   <li>A FAILED sign-in audits to the one pre-seeded {@code system}/{@code auth} chain (V0058) —
 *       {@code actor_kind='system'}, {@code actor_id=null} (there is no confirmed actor), payload
 *       {@code {"accountExists": bool, "userId": string|null}}. <strong>The submitted username
 *       never reaches this class and must never be added to its payload</strong> — a password typed
 *       into the username field would otherwise be permanently hash-chained into 7-year retention
 *       with no correction path (R1/R6 in the AD-002e research report).
 * </ul>
 */
@Component
public class AuthAuditRecorder {

  static final String OPERATOR_CHAIN_KIND = "operator";
  static final String SYSTEM_CHAIN_KIND = "system";
  static final String SYSTEM_AUTH_SUBJECT = "auth";

  static final String EVENT_SIGN_IN_SUCCEEDED = "sign_in_succeeded";
  static final String EVENT_SIGN_OUT = "sign_out";
  static final String EVENT_PASSWORD_CHANGED = "password_changed";
  static final String EVENT_SIGN_IN_FAILED = "sign_in_failed";

  private final AuditEventWriter auditEventWriter;
  private final OperatorUserRepository operatorUserRepository;

  public AuthAuditRecorder(
      AuditEventWriter auditEventWriter, OperatorUserRepository operatorUserRepository) {
    this.auditEventWriter = auditEventWriter;
    this.operatorUserRepository = operatorUserRepository;
  }

  public void signInSucceeded(UUID userId) {
    appendOperatorEvent(userId, EVENT_SIGN_IN_SUCCEEDED);
  }

  public void signedOut(UUID userId) {
    appendOperatorEvent(userId, EVENT_SIGN_OUT);
  }

  public void passwordChanged(UUID userId) {
    appendOperatorEvent(userId, EVENT_PASSWORD_CHANGED);
  }

  /**
   * @param accountExists whether {@code username} matched a real account — the only thing this
   *     method is ever told about the attempted credential
   * @param matchedUserId the matched account's UUID, or {@code null} when {@code accountExists} is
   *     false. Never a username, at any point in this method.
   */
  public void signInFailed(boolean accountExists, UUID matchedUserId) {
    // Map.of() rejects null values -- accountExists=false legitimately has a null userId, so a
    // mutable map is required here, not Map.of(...).
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountExists", accountExists);
    payload.put("userId", matchedUserId == null ? null : matchedUserId.toString());
    auditEventWriter.append(
        new AuditEvent(
            SYSTEM_CHAIN_KIND,
            SYSTEM_AUTH_SUBJECT,
            EVENT_SIGN_IN_FAILED,
            SYSTEM_CHAIN_KIND,
            null,
            null,
            null,
            UUID.randomUUID(),
            CanonicalJson.object(payload)));
  }

  private void appendOperatorEvent(UUID userId, String eventType) {
    operatorUserRepository.ensureOperatorChain(userId);
    auditEventWriter.append(
        new AuditEvent(
            OPERATOR_CHAIN_KIND,
            userId.toString(),
            eventType,
            "operator",
            userId.toString(),
            null,
            null,
            UUID.randomUUID(),
            CanonicalJson.object(Map.of())));
  }
}
