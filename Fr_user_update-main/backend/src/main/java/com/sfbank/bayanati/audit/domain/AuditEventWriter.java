package com.sfbank.bayanati.audit.domain;

/**
 * The one way application code writes to the audit trail.
 *
 * <p>A port, not a repository: it exposes append and nothing else, because {@code
 * audit.audit_event} is append-only at four independent levels (docs/components/persistence.md) and
 * there is no other legitimate operation.
 */
public interface AuditEventWriter {

  /**
   * Appends one event to its chain.
   *
   * <p>Must fail rather than return quietly if the event could not be written — an unaudited action
   * is worse than a failed one. There is no implied transaction: a caller decides for itself
   * whether to wrap a call to this method in one. {@link
   * com.sfbank.bayanati.accountcheck.service.AccountCheckService} deliberately does not, since a
   * rollback there would undo the very event this method exists to record.
   *
   * @return the new row's {@code audit_event_id}. Most callers fire-and-forget and ignore it
   *     (append-only means there is nothing to reference it later for); {@code
   *     app.profile_status_history.audit_event_id} is a {@code NOT NULL} foreign key onto it, so a
   *     caller creating a profile's first status-history row needs this value.
   * @throws IllegalStateException if the target chain does not exist
   */
  long append(AuditEvent event);

  /**
   * Writes {@code artifact} to {@code audit.audit_artifact} first, then appends {@code event}
   * referencing it via {@code audit_event.artifact_id} — one raw external-system exchange (a Uqudo
   * JWS, a Civil Registry response) stored byte-identical, alongside the hash-chained event rather
   * than inside its {@code payload_json} (see {@link AuditArtifact}'s Javadoc for why this
   * distinction matters for the 90-day PII purge rule).
   *
   * @return the new event's {@code audit_event_id}, exactly like {@link #append}
   * @throws IllegalStateException if the target chain does not exist
   */
  long appendWithArtifact(AuditEvent event, AuditArtifact artifact);
}
