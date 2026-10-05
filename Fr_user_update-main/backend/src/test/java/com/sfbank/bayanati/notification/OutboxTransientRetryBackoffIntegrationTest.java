package com.sfbank.bayanati.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.notification.domain.OutboxDrainSummary;
import com.sfbank.bayanati.notification.service.OutboxDispatcher;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S9-08, BL-169: a transient failure must not be retried inside the pass that just failed it.
 *
 * <p><strong>The defect.</strong> A TRANSIENT_FAILURE leaves the row {@code pending}, and {@code
 * recordResult} never reset {@code next_attempt_at} — so the row kept whatever the CLAIM had set,
 * which was claim time plus the claim lease. Any pass that outlived that lease found its own failed
 * row due again and sent it a second time. With Airtel's 5 s connect + 15 s read, two timed-out
 * sends in one pass already exceeded the 30 s lease that was then hardcoded. A transient failure is
 * exactly the case where the provider may have delivered the message anyway, so the duplicate is a
 * real second message to a real customer.
 *
 * <p><strong>How this proves it without waiting 30 seconds.</strong> The two intervals are now
 * separate properties, so this class shrinks them: a 1 s claim lease and a 5 s backoff. Three
 * messages at 400 ms of stub latency make a pass of roughly 1.2 s — comfortably longer than the
 * lease, which is the condition that used to trigger the bug, and far short of the backoff.
 *
 * <p>The assertion is {@code attempt_count}, which {@code CLAIM_ONE_PENDING} increments on every
 * claim. Exactly one claim per row is the whole property. No spy and no counter is needed: the
 * database already records how many times each message was picked up, which is the fact in question
 * rather than a proxy for it.
 *
 * <p>Account numbers here are TWELVE characters, where every other suite uses ten or eleven. That
 * is the technique {@code OtpVerificationIntegrationTest} established for the same reason — a
 * different length cannot collide with another suite's range even by numeric near-miss — and it
 * means this class consumes no part of the registry in {@link AbstractPostgresIntegrationTest}.
 */
@Tag("integration")
@SpringBootTest
class OutboxTransientRetryBackoffIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String ACCOUNT = "000000169001";
  private static final String DESTINATION = "+249900169001";
  private static final int MESSAGE_COUNT = 3;

  private static UUID profileId;
  private static boolean seeded;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NotificationOutboxRepository outboxRepository;
  @Autowired private OutboxDispatcher dispatcher;

  @DynamicPropertySource
  static void shortIntervalsAndASlowFailingGateway(DynamicPropertyRegistry registry) {
    // A 1 s lease with ~1.2 s of sending is the bug's precondition, reproduced in miniature.
    registry.add("fru.notification.outbox.claim-lease", () -> "1s");
    // Long enough that a correctly-backed-off row cannot come due during the pass; short enough
    // that this test costs a second, not twenty minutes like the shipped default.
    registry.add("fru.notification.outbox.transient-retry-backoff", () -> "5s");
    registry.add("fru.messaging.stub.latency-millis", () -> "400");
    registry.add("fru.messaging.stub.outcomes[" + DESTINATION + "]", () -> "TRANSIENT_FAILURE");
  }

  @BeforeEach
  void ensureProfileSeeded() throws SQLException {
    if (!seeded) {
      seedOneProfile();
      seeded = true;
    }
  }

  @Test
  void aTransientFailureIsNotRetriedInsideThePassThatFailedIt() {
    for (int i = 0; i < MESSAGE_COUNT; i++) {
      outboxRepository.enqueue(
          profileId, MessageChannel.SMS, DESTINATION, new SmsPayload("رسالة اختبار"));
    }

    long start = System.nanoTime();
    OutboxDrainSummary summary = dispatcher.drainPending();
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

    // The precondition: the pass really did outlive the claim lease. Without this the test could
    // pass for the wrong reason -- a fast pass never triggers the defect at all.
    assertTrue(
        elapsedMillis > 1000,
        "the pass must outlive the 1s claim lease for this test to mean anything; took "
            + elapsedMillis
            + "ms");

    assertEquals(
        MESSAGE_COUNT,
        summary.attempted(),
        "three messages, three attempts -- a fourth means one was claimed twice");

    List<Integer> attemptCounts =
        jdbcTemplate.queryForList(
            "SELECT attempt_count FROM app.notification_outbox WHERE destination = ?"
                + " ORDER BY created_at",
            Integer.class,
            DESTINATION);

    assertEquals(MESSAGE_COUNT, attemptCounts.size(), "all three rows should still be present");
    for (int i = 0; i < attemptCounts.size(); i++) {
      assertEquals(
          1,
          attemptCounts.get(i),
          "row " + i + " was claimed " + attemptCounts.get(i) + " times; exactly one is the point");
    }

    // All three stay pending: a transient failure is a retry, not a terminal state. What changed is
    // WHEN, not whether.
    Integer stillPending =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.notification_outbox"
                + " WHERE destination = ? AND state = 'pending'",
            Integer.class,
            DESTINATION);
    assertEquals(MESSAGE_COUNT, stillPending);
  }

  /** Minimal valid profile — the same technique {@code NotificationOutboxIntegrationTest} uses. */
  private static void seedOneProfile() throws SQLException {
    profileId = UUID.randomUUID();

    try (Connection migrator =
        DriverManager.getConnection(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD)) {
      migrator.setAutoCommit(false);

      execute(
          migrator,
          "INSERT INTO audit.audit_chain (chain_kind, subject_id, head_hash) VALUES ('profile', ?,"
              + " NULL)",
          profileId.toString());

      long auditEventId;
      try (PreparedStatement ps =
          migrator.prepareStatement(
              """
              INSERT INTO audit.audit_event
                (chain_id, seq, occurred_at, event_type, actor_kind, actor_id,
                 profile_id, session_id, request_id, payload_json,
                 prev_hash, content_hash, row_hash)
              SELECT c.chain_id, 0, clock_timestamp(), 'profile_created', 'system', NULL,
                     ?::uuid, NULL, NULL, '{}',
                     ''::bytea, ''::bytea, ''::bytea
                FROM audit.audit_chain c
               WHERE c.chain_kind = 'profile' AND c.subject_id = ?
              RETURNING audit_event_id
              """)) {
        ps.setString(1, profileId.toString());
        ps.setString(2, profileId.toString());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          auditEventId = rs.getLong(1);
        }
      }

      execute(
          migrator,
          """
          INSERT INTO app.profile
            (profile_id, branch_code, account_number, status, status_changed_at, last_activity_at)
          VALUES (?::uuid, '16', ?, 'in_progress', clock_timestamp(), clock_timestamp())
          """,
          profileId.toString(),
          ACCOUNT);

      execute(
          migrator,
          """
          INSERT INTO app.profile_status_history
            (profile_id, seq, from_status, to_status, occurred_at, actor_kind, actor_id,
             audit_event_id)
          VALUES (?::uuid, 1, NULL, 'in_progress', clock_timestamp(), 'system', NULL, ?)
          """,
          profileId.toString(),
          auditEventId);

      execute(
          migrator,
          """
          INSERT INTO app.profile_channel (profile_id, channel, state, verified_at)
          VALUES (?::uuid, 'sms', 'verified', clock_timestamp())
          """,
          profileId.toString());

      migrator.commit();
    }
  }

  private static void execute(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      ps.execute();
    }
  }
}
