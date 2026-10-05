package com.sfbank.bayanati.notification.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code reads and writes {@code app.notification_outbox} (V0035, designed by
 * the AD-005 report §6A). A port, mirroring {@code audit.domain.AuditEventWriter}'s shape:
 * everything this table needs done, nothing about how it is done.
 */
public interface NotificationOutboxRepository {

  /**
   * Enqueues one notification for one verified channel. AD-005 §6A: in the real journey this is
   * called inside the same transaction as a status update, its history row and its audit event —
   * that call site does not exist yet (stage 1b/11 profile persistence, out of scope here).
   *
   * @return the new row's {@code outbox_id}, which doubles as the eventual {@code
   *     OutboundMessage.messageId()}
   */
  UUID enqueue(UUID profileId, MessageChannel channel, String destination, MessagePayload payload);

  /**
   * Claims at most one pending, due row for dispatch, per the AD-005 §6 poll query ({@code state =
   * 'pending' AND next_attempt_at <= clock_timestamp() ... FOR UPDATE SKIP LOCKED}), and takes out
   * a short lease on it (advances {@code next_attempt_at}) so a second, concurrent caller running
   * the same query does not immediately claim it again.
   *
   * <p><strong>Scope note:</strong> proves the shape for one manual dispatch, per S3-05's explicit
   * scope. A real multi-instance scheduled runner needs a batch form of this (AD-005's {@code LIMIT
   * 50}) and a considered lease duration — later work, not invented here.
   */
  Optional<ClaimedOutboxEntry> claimOnePending();

  /**
   * Writes one dispatch attempt's result back onto its row — the columns V0035 shapes for exactly
   * this.
   */
  void recordResult(UUID outboxId, MessageDispatchResult result);
}
