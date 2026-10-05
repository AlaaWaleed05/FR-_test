package com.sfbank.bayanati.notification.jdbc;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import com.sfbank.bayanati.messaging.domain.MessagePayloadJson;
import com.sfbank.bayanati.notification.domain.ClaimedOutboxEntry;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.notification.domain.OutboxState;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes and reads {@code app.notification_outbox} (V0035) as {@code fru_app}. The single place in
 * the application that knows the shape of that table — mirrors {@code JdbcAuditEventWriter}'s
 * single-writer discipline.
 */
@Repository
public class JdbcNotificationOutboxRepository implements NotificationOutboxRepository {

  private static final String INSERT =
      """
      INSERT INTO app.notification_outbox (profile_id, channel, destination, payload)
      VALUES (?::uuid, ?::text, ?::text, ?::jsonb)
      RETURNING outbox_id
      """;

  /**
   * Claims one due row and takes out a short lease on it in the same statement, by advancing {@code
   * next_attempt_at} — see {@code NotificationOutboxRepository.claimOnePending} for why: a plain
   * {@code SELECT ... FOR UPDATE} releases its lock the instant this statement's own implicit
   * (autocommit) transaction commits, which would let a second, concurrent poll re-claim the same
   * row before this one finishes. The lease closes that window for the one-at-a-time dispatch this
   * task proves; a real scheduler needs a considered lease duration, not invented here.
   */
  private static final String CLAIM_ONE_PENDING =
      """
      UPDATE app.notification_outbox
         SET attempt_count = attempt_count + 1,
             next_attempt_at = clock_timestamp() + make_interval(secs => ?)
       WHERE outbox_id = (
         SELECT outbox_id FROM app.notification_outbox
          WHERE state = 'pending' AND next_attempt_at <= clock_timestamp()
          ORDER BY next_attempt_at
          FOR UPDATE SKIP LOCKED LIMIT 1
       )
      RETURNING outbox_id, profile_id, channel, destination, payload::text AS payload_text
      """;

  /**
   * Applied by {@link #recordResult} when, and only when, the provider outcome leaves the row
   * {@code pending} — a TRANSIENT_FAILURE. See the class Javadoc for why this is separate from the
   * claim lease above.
   */
  private static final String SET_TRANSIENT_RETRY_BACKOFF =
      """
      UPDATE app.notification_outbox
         SET next_attempt_at = clock_timestamp() + make_interval(secs => ?)
       WHERE outbox_id = ?::uuid
      """;

  /**
   * One-to-one with {@link MessageDispatchResult}'s fields — see V0035's own comment. {@code state}
   * is derived from {@code outcome} by {@link
   * OutboxState#forOutcome(com.sfbank.bayanati.messaging.domain.DispatchOutcome)}, not decided
   * here: TRANSIENT_FAILURE stays pending, with the lease {@link #CLAIM_ONE_PENDING} already
   * advanced acting as the retry backoff.
   */
  private static final String RECORD_RESULT =
      """
      UPDATE app.notification_outbox
         SET state = ?::text,
             outcome = ?::text,
             provider_id = ?::text,
             provider_message_id = ?::text,
             provider_status_code = ?::text,
             provider_status_text = ?::text,
             billed_segments = ?::int,
             attempted_at = ?::timestamptz,
             latency_millis = ?::bigint
       WHERE outbox_id = ?::uuid
      """;

  private final JdbcTemplate jdbcTemplate;
  private final double claimLeaseSeconds;
  private final double transientRetryBackoffSeconds;

  public JdbcNotificationOutboxRepository(
      JdbcTemplate jdbcTemplate,
      @Value("${fru.notification.outbox.claim-lease}") Duration claimLease,
      @Value("${fru.notification.outbox.transient-retry-backoff}") Duration transientRetryBackoff) {
    this.jdbcTemplate = jdbcTemplate;
    this.claimLeaseSeconds = claimLease.toMillis() / 1000.0;
    this.transientRetryBackoffSeconds = transientRetryBackoff.toMillis() / 1000.0;
  }

  @Override
  public UUID enqueue(
      UUID profileId, MessageChannel channel, String destination, MessagePayload payload) {
    String outboxId =
        jdbcTemplate.queryForObject(
            INSERT,
            new Object[] {
              profileId.toString(),
              channel.wireValue(),
              destination,
              MessagePayloadJson.toJson(payload)
            },
            new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR},
            String.class);
    return UUID.fromString(outboxId);
  }

  @Override
  public Optional<ClaimedOutboxEntry> claimOnePending() {
    List<ClaimedOutboxEntry> claimed =
        jdbcTemplate.query(
            CLAIM_ONE_PENDING,
            new Object[] {claimLeaseSeconds},
            new int[] {Types.DOUBLE},
            (rs, rowNum) -> {
              MessageChannel channel = channelForWireValue(rs.getString("channel"));
              return new ClaimedOutboxEntry(
                  UUID.fromString(rs.getString("outbox_id")),
                  UUID.fromString(rs.getString("profile_id")),
                  channel,
                  rs.getString("destination"),
                  MessagePayloadJson.fromJson(channel, rs.getString("payload_text")));
            });
    return claimed.stream().findFirst();
  }

  @Override
  public void recordResult(UUID outboxId, MessageDispatchResult result) {
    int rowsUpdated =
        jdbcTemplate.update(
            RECORD_RESULT,
            new Object[] {
              OutboxState.forOutcome(result.outcome()).wireValue(),
              result.outcome().name(),
              result.providerId(),
              result.providerMessageId(),
              result.providerStatusCode(),
              result.providerStatusText(),
              result.billedSegments(),
              result.attemptedAtIso(),
              result.latencyMillis(),
              outboxId.toString()
            },
            new int[] {
              Types.VARCHAR,
              Types.VARCHAR,
              Types.VARCHAR,
              Types.VARCHAR,
              Types.VARCHAR,
              Types.VARCHAR,
              Types.INTEGER,
              Types.VARCHAR,
              Types.BIGINT,
              Types.VARCHAR
            });

    if (rowsUpdated != 1) {
      throw new EmptyResultDataAccessException(
          "no app.notification_outbox row for outbox_id " + outboxId + " — result was not recorded",
          1);
    }

    // THE DUPLICATE-SEND FIX (BL-169). A TRANSIENT_FAILURE leaves the row `pending`, and until
    // S9-08 nothing reset its next_attempt_at -- so it kept whatever the CLAIM set, i.e. claim time
    // plus the claim lease. A pass that ran longer than that lease therefore found the row due
    // AGAIN, inside the same pass, and sent it a second time. A transient failure is exactly the
    // case where the message may well have been delivered anyway, so the duplicate is a real
    // message to a real customer.
    //
    // OutboxDispatcher's Javadoc had recorded this and deferred it to "the real provider adapter";
    // Airtel shipped, with a 5s connect + 15s read timeout, so two timed-out sends in one pass
    // already exceeded a 30s lease. The deferral's stated precondition had arrived.
    if (OutboxState.forOutcome(result.outcome()) == OutboxState.PENDING) {
      jdbcTemplate.update(
          SET_TRANSIENT_RETRY_BACKOFF,
          new Object[] {transientRetryBackoffSeconds, outboxId.toString()},
          new int[] {Types.DOUBLE, Types.VARCHAR});
    }
  }

  private static MessageChannel channelForWireValue(String wireValue) {
    for (MessageChannel channel : MessageChannel.values()) {
      if (channel.wireValue().equals(wireValue)) {
        return channel;
      }
    }
    throw new IllegalStateException("unrecognised channel wire value from database: " + wireValue);
  }
}
