package com.sfbank.bayanati.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import com.sfbank.bayanati.notification.domain.ClaimedOutboxEntry;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.notification.scheduler.OutboxDispatchScheduler;
import com.sfbank.bayanati.notification.service.OutboxDispatcher;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * S3-05: the outbox and the port end to end — a real row enqueued, claimed, sent through the stub,
 * its result written back onto the row, and a {@code notification_dispatched} audit event appended,
 * all against a real PostgreSQL 18 with every migration applied.
 *
 * <p>Tagged "integration" and excluded from ./mvnw test / verify by default — run with {@code
 * ./mvnw test -Pdb-integration-test}. Container and dynamic properties come from {@link
 * AbstractPostgresIntegrationTest} — S3-09 moved every integration class onto one shared,
 * JVM-lifetime container instead of one each; this class's fixed account 0000000099 is disjoint
 * from every other integration class's range (see that class's Javadoc for the full map).
 *
 * <p><strong>Fixture note.</strong> {@code app.notification_outbox} FKs to a real {@code
 * app.profile}, and a {@code notification_dispatched} event needs a real {@code profile} audit
 * chain to append to — but no application code creates either yet (profile persistence is BL-006,
 * out of scope here; {@code fru_app} cannot create an audit chain at all, V0004). {@link
 * #seedOneProfile()} builds a minimal, fully valid profile by hand, once, as {@code fru_migrator}
 * (the same elevated-connection technique S2-04's tamper tests used): a {@code profile} chain, a
 * genesis audit event, the {@code app.profile} row through the legal {@code NULL -> in_progress}
 * transition, its matching {@code profile_status_history} row (the deferred constraint trigger
 * requires it in the same transaction), and one verified {@code sms} channel. This is test fixture
 * setup, not a claim that this is how a profile will really be created.
 *
 * <p>{@code @TestMethodOrder}: {@link
 * #theProfileChainStillVerifiesAfterTheNotificationEventLandsOnIt} asserts something the end-to-end
 * test's own event puts on the chain, so it must run after it — JUnit does not order methods by
 * default, and without this a scrambled order lets that assertion pass vacuously against a chain
 * holding only the genesis event.
 */
@Tag("integration")
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationOutboxIntegrationTest extends AbstractPostgresIntegrationTest {

  private static UUID profileId;

  @Autowired private JdbcTemplate jdbcTemplate; // connects as fru_app
  @Autowired private NotificationOutboxRepository outboxRepository;
  @Autowired private OutboxDispatcher dispatcher;
  @Autowired private OutboxDispatchScheduler scheduler;

  // required = false deliberately: with @EnableScheduling absent this bean does not exist at all,
  // and a required injection would fail the whole class's context before any test ran. Optional
  // injection keeps that failure where it belongs — one named assertion in
  // theScheduledTaskIsActuallyRegistered, with a message that says what is actually wrong.
  @Autowired(required = false)
  private ScheduledTaskHolder scheduledTaskHolder;

  private static final AtomicBoolean PROFILE_SEEDED = new AtomicBoolean(false);

  /**
   * NOT {@code @BeforeAll}: a static {@code @BeforeAll} runs before the Spring context is prepared,
   * and therefore before Flyway has applied a single migration — found live, the first run of this
   * test failed with "relation audit.audit_chain does not exist". {@code @BeforeEach} runs after
   * {@code @Autowired} injection, which only happens once the context (Flyway included) is up; the
   * {@link AtomicBoolean} makes the actual seeding run exactly once across this class's tests.
   */
  @BeforeEach
  void ensureProfileSeeded() throws SQLException {
    if (PROFILE_SEEDED.compareAndSet(false, true)) {
      seedOneProfile();
    }
  }

  private static void seedOneProfile() throws SQLException {
    profileId = UUID.randomUUID();

    try (Connection migrator =
        DriverManager.getConnection(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD)) {
      migrator.setAutoCommit(false);

      execute(
          migrator,
          "INSERT INTO audit.audit_chain (chain_kind, subject_id, head_hash) VALUES ('profile', ?, NULL)",
          profileId.toString());

      // Same placeholder-hash shape as JdbcAuditEventWriter.INSERT_EVENT: the chain_append()
      // trigger (V0003) overwrites seq/occurred_at/prev_hash/content_hash/row_hash.
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
          VALUES (?::uuid, '16', '0000000099', 'in_progress', clock_timestamp(), clock_timestamp())
          """,
          profileId.toString());

      execute(
          migrator,
          """
          INSERT INTO app.profile_status_history
            (profile_id, seq, from_status, to_status, occurred_at, actor_kind, actor_id, audit_event_id)
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

      migrator.commit(); // this is when the deferred profile_status_requires_history trigger fires
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

  @Test
  @Order(1)
  void endToEndDispatchThroughTheStubUpdatesTheOutboxRowAndWritesTheAuditEvent() {
    UUID outboxId =
        outboxRepository.enqueue(
            profileId, MessageChannel.SMS, "+249900000000", new SmsPayload("رمز التحقق: 483920"));

    Optional<MessageDispatchResult> result = dispatcher.dispatchOnePending();

    assertTrue(result.isPresent(), "the row just enqueued must be the one claimed");
    assertEquals(outboxId.toString(), result.get().messageId());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT state, outcome, provider_id, billed_segments FROM app.notification_outbox"
                + " WHERE outbox_id = ?::uuid",
            outboxId.toString());
    assertEquals("dispatched", row.get("state"));
    assertEquals("ACCEPTED", row.get("outcome"));
    assertEquals("stub", row.get("provider_id"));
    assertEquals(1, ((Number) row.get("billed_segments")).intValue());

    Long dispatchedEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event"
                + " WHERE event_type = 'notification_dispatched' AND profile_id = ?::uuid",
            Long.class,
            profileId.toString());
    assertEquals(1L, dispatchedEvents);
  }

  @Test
  @Order(2)
  void theProfileChainStillVerifiesAfterTheNotificationEventLandsOnIt() {
    // Not vacuous: asserts the chain actually contains the notification_dispatched event the
    // Order(1) test wrote, not merely that an (otherwise-empty) chain verifies.
    Long dispatchedEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event"
                + " WHERE event_type = 'notification_dispatched' AND profile_id = ?::uuid",
            Long.class,
            profileId.toString());
    assertEquals(
        1L, dispatchedEvents, "expected the Order(1) test's event to already be on the chain");

    Map<String, Object> verification =
        jdbcTemplate.queryForMap(
            "SELECT ok, reason FROM audit.verify_chain("
                + "(SELECT chain_id FROM audit.audit_chain WHERE chain_kind='profile' AND subject_id=?))",
            profileId.toString());

    assertEquals(
        Boolean.TRUE,
        verification.get("ok"),
        "chain verification failed: " + verification.get("reason"));
  }

  @Test
  @Order(3)
  void aSecondClaimImmediatelyAfterTheFirstFindsNothingDueToTheLease() {
    UUID outboxId =
        outboxRepository.enqueue(
            profileId,
            MessageChannel.WHATSAPP,
            "+249900000001",
            new WhatsAppPayload(
                "otp", "ar", List.of("123456"), WhatsAppTemplateCategory.AUTHENTICATION));

    Optional<ClaimedOutboxEntry> first = outboxRepository.claimOnePending();
    assertTrue(first.isPresent(), "the row just enqueued must be claimable");
    assertEquals(outboxId, first.get().outboxId());

    // Immediately claiming again must find nothing: CLAIM_ONE_PENDING's lease advanced
    // next_attempt_at past "now", so a concurrent second poller does not re-claim the same row
    // before the first finishes (JdbcNotificationOutboxRepository's own Javadoc claim).
    Optional<ClaimedOutboxEntry> second = outboxRepository.claimOnePending();
    assertTrue(
        second.isEmpty(), "the lease should have hidden the row from a second, immediate claim");
  }

  @Test
  @Order(4)
  void recordingAResultForAnUnknownOutboxIdFailsRatherThanSilentlyDoingNothing() {
    UUID unknownOutboxId = UUID.randomUUID();
    MessageDispatchResult result =
        MessageDispatchResult.permanentFailure(
            new OutboundMessage(
                unknownOutboxId,
                MessageChannel.SMS,
                "+249900000000",
                new SmsPayload("body"),
                Urgency.DEFERRED,
                "corr"),
            "stub",
            "X",
            "boom");

    assertThrows(
        EmptyResultDataAccessException.class,
        () -> outboxRepository.recordResult(unknownOutboxId, result));
  }

  @Test
  @Order(5)
  void theChannelCheckConstraintAcceptsAllThreeLowercaseValuesAndRejectsWrongCase() {
    // whatsapp and email: insert succeeds (sms is already proven by the end-to-end test above).
    // next_attempt_at is pushed an hour into the future so these rows -- an empty '{}' payload,
    // not a real WhatsAppPayload/EmailPayload shape -- are never picked up by a claimOnePending()
    // call from an earlier-ordered test. @TestMethodOrder now fixes this class's run order, but
    // the table is still shared across all of its tests by design (one seeded profile, per the
    // class Javadoc), so a future test insertion could still collide without this guard.
    assertDoesNotThrow(
        () ->
            jdbcTemplate.update(
                "INSERT INTO app.notification_outbox (profile_id, channel, destination, payload, next_attempt_at)"
                    + " VALUES (?::uuid, 'whatsapp', '+249900000000', '{}'::jsonb, clock_timestamp() + interval '1 hour')",
                profileId.toString()));
    assertDoesNotThrow(
        () ->
            jdbcTemplate.update(
                "INSERT INTO app.notification_outbox (profile_id, channel, destination, payload, next_attempt_at)"
                    + " VALUES (?::uuid, 'email', 'customer@example.invalid', '{}'::jsonb, clock_timestamp() + interval '1 hour')",
                profileId.toString()));

    // A wrong-case value is rejected by the CHECK constraint, not silently accepted.
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbcTemplate.update(
                "INSERT INTO app.notification_outbox (profile_id, channel, destination, payload)"
                    + " VALUES (?::uuid, 'SMS', '+249900000000', '{}'::jsonb)",
                profileId.toString()));
  }

  // --- S6-04: the scheduled runner ---------------------------------------------------------

  /**
   * The proof S6-04 actually needs: four rows enqueued, ONE scheduler tick, all four resolved.
   * Deliberately calls {@link OutboxDispatchScheduler#pollOutbox()} — the method the timer calls —
   * and not {@code dispatchOnePending()} or {@code drainPending()}, because the defect this task
   * fixes was that nothing wired a caller to the dispatcher at all; a test that reaches past the
   * scheduler would pass just as happily against the broken version.
   *
   * <p>Triggered directly rather than by waiting on wall-clock time: {@link
   * AbstractPostgresIntegrationTest} pushes the real timer an hour out for every integration
   * context, on purpose (see its Javadoc).
   */
  @Test
  @Order(6)
  void oneScheduledTickDrainsEveryPendingRow() {
    // Make the tick deterministic. claimOnePending() is unscoped, so it would otherwise also pick
    // up the Order(3) test's claimed-but-never-recorded row (its 30 s lease may well have expired
    // by now) and anything another integration class left due in this shared container. Hiding
    // them the same way every enqueuing test in this suite already does — pushing next_attempt_at
    // an hour out — leaves this test's own four rows as the only claimable ones.
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = clock_timestamp() + interval '1 hour'"
            + " WHERE state = 'pending'");

    long eventsBefore = dispatchedEventCount();

    // Three the stub accepts, and one it rejects — +249900000001 is seeded to REJECTED in
    // application.properties. The rejected row is enqueued in the middle deliberately: if a bad
    // message blocked the queue, the rows behind it would still be pending at the end.
    UUID first = enqueueSms("+249900000000", "تم استلام طلبك");
    UUID rejected = enqueueSms("+249900000001", "تم استلام طلبك");
    UUID third = enqueueSms("+249900000000", "تمت الموافقة على ملفك");
    UUID fourth = enqueueSms("+249900000000", "تم تحديث حالة ملفك");

    scheduler.pollOutbox();

    assertEquals("dispatched", stateOf(first));
    assertEquals("dispatched", stateOf(third));
    assertEquals("dispatched", stateOf(fourth));
    // REJECTED is terminal, never retried (OutboxState.forOutcome) — and, the point here, it did
    // not stop the two rows enqueued after it from being sent in the same tick.
    assertEquals("failed", stateOf(rejected));

    // Every attempt is audited, the failed one included: four rows in, four
    // notification_dispatched events on the profile's chain.
    assertEquals(eventsBefore + 4, dispatchedEventCount());
  }

  /**
   * That {@code pollOutbox()} drains when called is only half the claim — the other half is that
   * something calls it. This asserts the {@code @Scheduled} method is actually registered with the
   * container's scheduler, which is what {@code @EnableScheduling} on {@code BackendApplication}
   * buys. Remove that annotation and the Order(6) test above still passes; this one does not.
   */
  @Test
  @Order(7)
  void theScheduledTaskIsActuallyRegistered() {
    assertNotNull(
        scheduledTaskHolder,
        "no ScheduledTaskHolder in the context — @EnableScheduling is missing, so no @Scheduled"
            + " method anywhere in this application is ever called");

    Set<ScheduledTask> tasks = scheduledTaskHolder.getScheduledTasks();
    List<String> registered = tasks.stream().map(Object::toString).toList();

    assertTrue(
        registered.stream().anyMatch(task -> task.contains("OutboxDispatchScheduler.pollOutbox")),
        "no scheduled task registered for the outbox drain; registered tasks: " + registered);
  }

  private UUID enqueueSms(String destination, String body) {
    return outboxRepository.enqueue(
        profileId, MessageChannel.SMS, destination, new SmsPayload(body));
  }

  private String stateOf(UUID outboxId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM app.notification_outbox WHERE outbox_id = ?::uuid",
        String.class,
        outboxId.toString());
  }

  private long dispatchedEventCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit.audit_event"
            + " WHERE event_type = 'notification_dispatched' AND profile_id = ?::uuid",
        Long.class,
        profileId.toString());
  }
}
