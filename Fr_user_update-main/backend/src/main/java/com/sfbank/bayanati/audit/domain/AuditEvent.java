package com.sfbank.bayanati.audit.domain;

import java.util.UUID;

/**
 * One row destined for {@code audit.audit_event}, carrying only the columns an application may
 * supply.
 *
 * <p>Deliberately absent: {@code seq}, {@code occurred_at}, {@code prev_hash}, {@code content_hash}
 * and {@code row_hash}. Those belong to {@code audit.chain_append()} (V0003), which overwrites
 * whatever an application sends. Modelling them here would invite a caller to believe it owns them.
 *
 * @param chainKind which chain to append to — {@code profile}, {@code operator} or {@code system}
 * @param chainSubject the chain's subject id; for {@code system} chains a fixed name such as {@code
 *     account_check}
 * @param eventType the controlled-vocabulary event type, e.g. {@code account_check_attempted}
 * @param actorKind {@code customer}, {@code operator} or {@code system}
 * @param actorId the actor's identifier, or {@code null} where there is none — an account check
 *     happens before any session or profile exists, so there is nobody to name
 * @param profileId the profile this event concerns, or {@code null}. Null is expected, not
 *     exceptional: the journey requires account-check attempts recorded even when no profile is
 *     created
 * @param sessionId the journey session, or {@code null} before Stage 1b creates one
 * @param requestId correlates this event with the HTTP request that produced it
 * @param payloadJson RFC 8785 canonical JSON — build it with {@link CanonicalJson}, never by string
 *     concatenation. It is the text the hash chain covers, and the source of the generated {@code
 *     payload} jsonb column
 */
public record AuditEvent(
    String chainKind,
    String chainSubject,
    String eventType,
    String actorKind,
    String actorId,
    UUID profileId,
    UUID sessionId,
    UUID requestId,
    String payloadJson) {}
