package com.sfbank.bayanati.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.EmailPayload;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.notification.service.OutboxDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S9-08: the capacity measurement behind AP-7. Tagged {@code load} and therefore in NO gate — it
 * sleeps for minutes on purpose. Run it deliberately:
 *
 * <pre>./mvnw test -Pload-test</pre>
 *
 * <p>Results are appended to {@code target/capacity-measurement.txt} so the hosting document can
 * cite a file rather than a console nobody kept.
 *
 * <p><strong>What this can and cannot establish, stated here because the numbers leave this repo
 * and go to a bank.</strong> It runs on a developer machine where the application JVM and the
 * PostgreSQL container share the same few cores. The specification sizes SRV-APP and SRV-DB as two
 * separate machines. So this CANNOT produce a submissions-per-hour ceiling for the bank's hardware,
 * and any figure presented as one would be worse than the unmeasured range AP-7 carries today,
 * because it would borrow the authority of a measurement.
 *
 * <p>What it CAN establish is what AP-7 actually needs: which limit binds first, the per-operation
 * costs that make the current range wide, and whether the queue model below is real. Those transfer
 * across hardware far better than a throughput number does.
 *
 * <p><strong>The model, and the correction review forced on it.</strong> The dispatcher sends
 * sequentially, at most {@code MAX_ATTEMPTS_PER_TICK} = 50 per tick, with {@code fixedDelay} P
 * measured from the END of the previous tick. The outbox carries up to 6 messages per submission:
 * one per VERIFIED channel at submission, and one per verified channel again on the terminal
 * transition. The inline OTPs never enter it. TWO QUALIFICATIONS that figure must carry wherever it
 * is published: the shipped configuration gives a real gateway to SMS only, so a real deployment is
 * 2 messages per submission rather than 6; and 6 is the happy path, since a rejected, resubmitted
 * and then approved profile pays for every terminal transition it reaches.
 *
 * <p>A first version of this javadoc modelled only the QUEUE, giving cycle time {@code P/(1-λL)}
 * and saturation at {@code L = 1/λ} = 6 s at the design load, and concluded that shortening P
 * "divides the DELAY but leaves the CEILING alone, because 1/λ contains no P". The saturation
 * figure is right. THE CONCLUSION WAS BACKWARDS, because it ignored the per-tick cap. With the cap
 * the ceiling is
 *
 * <pre>  50 messages / (P + 50L)  =  30000 / (P + 50L)  submissions per hour</pre>
 *
 * <p>which contains P and is dominated by it whenever the gateway is fast. At the measured
 * dispatcher floor the capped ceiling is about 971 submissions/hour where the uncapped model
 * claimed 33,000 — wrong by a factor of 34. P = 30 s also imposes an ABSOLUTE ceiling near 1000
 * submissions/hour however fast the gateway becomes, which the hosting document's AP-7 currently
 * contradicts by publishing an upper bound of 1,500.
 *
 * <p>So shortening the poll interval is the cheapest lever this system has, not a no-op: P = 5 s
 * raises that absolute ceiling to roughly 6000/hour. It is NOT changed here, because the 30 s lease
 * in {@code JdbcNotificationOutboxRepository} is tied to this interval and must be fixed first
 * (BL-169).
 *
 * <p><strong>The cadence term is DERIVED, not measured.</strong> M1 drives one tick of depth 20,
 * under the cap, and never varies P, so the cap and the interval enter the arithmetic from the code
 * rather than from a stopwatch. Anything the document says about them has to say that too.
 */
@Tag("load")
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CapacityMeasurementTest extends AbstractPostgresIntegrationTest {

  /**
   * Account-number range reserved for this class in {@link AbstractPostgresIntegrationTest}'s
   * registry. Synthetic sequential placeholders — no real account number, per CLAUDE.md.
   */
  private static final String ACCOUNT = "0000000466";

  /**
   * M2 needs a PROFILE PER MEASUREMENT, not a profile per class. V0059 puts a partial unique index
   * on {@code (profile_id) WHERE kind = 'salary_certificate'}, so one profile can hold exactly one
   * certificate -- the second write hits a duplicate key. Found by running this.
   *
   * <p>Accounts are drawn in sequence from this class's reserved range, 0000000466-0000000480, and
   * nextAccount() fails loudly rather than wrapping past its end: silently reusing another suite's
   * account number is the one failure the registry in AbstractPostgresIntegrationTest prevents.
   */
  private static final int ACCOUNT_RANGE_END = 480;

  private static final AtomicInteger NEXT_ACCOUNT = new AtomicInteger(467);

  private static final Path RESULTS = Path.of("target", "capacity-measurement.txt");

  /** Injected per-send gateway latency, in milliseconds. Read by the spy on every send. */
  private static final AtomicLong SEND_LATENCY_MILLIS = new AtomicLong(0);

  private static final AtomicBoolean HEADER_WRITTEN = new AtomicBoolean(false);

  private static UUID profileId;
  private static boolean seeded;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NotificationOutboxRepository outboxRepository;
  @Autowired private OutboxDispatcher dispatcher;
  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * Gateway latency is injected here rather than through {@code fru.messaging.stub.latency-millis},
   * which exists and would otherwise be the right seam. The property binds once at startup into an
   * immutable record, so using it would mean one Spring context per latency value — and each
   * context costs a 20-connection pool and a full Flyway migration. A spy varies it per test at no
   * structural cost. The overhead of the spy itself is irrelevant at the scale being measured: it
   * records an invocation while the thing being timed sleeps for hundreds of milliseconds.
   */
  @MockitoSpyBean private MessageSender messageSender;

  @BeforeEach
  void prepare() throws SQLException {
    doAnswer(
            invocation -> {
              long latency = SEND_LATENCY_MILLIS.get();
              if (latency > 0) {
                Thread.sleep(latency);
              }
              return invocation.callRealMethod();
            })
        .when(messageSender)
        .send(any());

    if (!seeded) {
      profileId = seedProfile(ACCOUNT);
      seeded = true;
    }
  }

  // ---------------------------------------------------------------- M1: the outbox queue model

  @Test
  @Order(1)
  void outboxTickCostIsLinearInQueueDepthAndGatewayLatency() {
    record Point(int depth, long latencyMillis, int attempted, long elapsedMillis) {}
    List<Point> points = new ArrayList<>();

    // One discarded warm-up drain, for the reason M2 has one: without it the first latency measured
    // carries all the JIT and statement-plan warming for the whole dispatch path, and the per-send
    // floor -- the number that feeds the ceiling arithmetic -- is the figure most distorted by it.
    enqueueOne();
    dispatcher.drainPending();

    for (long latency : new long[] {0L, 20L, 100L}) {
      SEND_LATENCY_MILLIS.set(latency);
      int depth = 20;
      for (int i = 0; i < depth; i++) {
        enqueueOne();
      }

      long start = System.nanoTime();
      var summary = dispatcher.drainPending();
      long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();

      points.add(new Point(depth, latency, summary.attempted(), elapsed));
      assertEquals(
          depth, summary.attempted(), "every enqueued row should be attempted in one tick");

      // THE CLAIM UNDER TEST: sends are sequential, so a tick costs at least depth x latency. If
      // the dispatcher were ever made concurrent this assertion fails, which is the point -- the
      // whole AP-7 ceiling is derived from sends being one after another.
      assertTrue(
          elapsed >= depth * latency,
          "tick of "
              + depth
              + " at "
              + latency
              + "ms took "
              + elapsed
              + "ms; expected >= "
              + (depth * latency)
              + "ms if sends are sequential");
    }

    SEND_LATENCY_MILLIS.set(0);

    StringBuilder report = new StringBuilder("M1 OUTBOX TICK COST (sequential dispatch)\n");
    for (Point p : points) {
      double perSend = p.elapsedMillis() / (double) p.attempted();
      report.append(
          String.format(
              "  depth=%d injected-latency=%dms -> attempted=%d elapsed=%dms (%.1f ms/send)%n",
              p.depth(), p.latencyMillis(), p.attempted(), p.elapsedMillis(), perSend));
    }
    // Per-send overhead at zero injected latency is the dispatcher's own cost: a claim UPDATE, a
    // result UPDATE, an audit append (itself a FOR UPDATE on that profile's own audit-chain row and
    // an UPDATE of it), the surrounding begin/commit, and one trailing claim that finds nothing --
    // elapsed/20 across 21 round trips. That is the floor total per-message cost cannot go below,
    // whatever the gateway does.
    double floorMillis = points.get(0).elapsedMillis() / (double) points.get(0).attempted();
    report.append(
        String.format("  dispatcher floor (no gateway) = %.1f ms per message%n", floorMillis));

    // Two ceilings; the smaller is always the real one. DERIVED FROM THE CODE, NOT TIMED HERE:
    // MAX_ATTEMPTS_PER_TICK = 50, poll interval = 30 s.
    report.append("  ceiling in submissions/hour, by TOTAL per-message cost L:\n");
    report.append("       L(s)   queue-only 600/L   capped 30000/(30+50L)  <- real\n");
    for (double totalCost : new double[] {floorMillis / 1000.0, 0.1, 0.4, 1.0, 2.0, 5.4}) {
      report.append(
          String.format(
              "     %6.3f %16.0f %22.0f%n",
              totalCost, 600.0 / totalCost, 30000.0 / (30.0 + 50.0 * totalCost)));
    }
    report.append(
        "  => the QUEUE alone saturates at the design load at L = 6.0 s, but the per-tick cap and\n"
            + "     the 30 s poll interval bind FIRST at every latency: an absolute ceiling near\n"
            + "     1000 submissions/hour however fast the gateway is, and the design load\n"
            + "     saturating at L = 5.4 s rather than 6.0 s. Shortening the poll interval raises\n"
            + "     that absolute ceiling roughly in proportion (P = 5 s gives about 6000/hour). It\n"
            + "     is the cheapest lever available, and is blocked only by BL-169's lease.\n");
    record(report.toString());
  }

  // ------------------------------------------------- M2: how long 4.8 MB holds a connection

  @Test
  @Order(2)
  void documentImageWriteHoldsItsConnectionForTheDurationOfTheWrite() throws SQLException {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    StringBuilder report = new StringBuilder("M2 IMAGE-WRITE CONNECTION HOLD (bytea, EXTERNAL)\n");

    // 4.8 MB is section 4.1's per-journey figure; the smaller sizes are there so the result is a
    // RATE rather than a single number, because a rate is the part that survives being moved to
    // different hardware.
    // ONE DISCARDED WARM-UP WRITE. Without it the first size measured carries the whole cost of
    // JIT, the first connection draw and the first TOAST write, and the result is not merely noisy
    // but NON-MONOTONIC: the first clean run of this measurement reported 1 MB at 251 ms and 5 MB
    // at 185 ms, which would have told the bank a smaller write costs more than a larger one. The
    // warm-up is discarded rather than reported, and this comment is why it exists at all.
    writeOnce(transactionTemplate, 1);

    int[] sizes = {1, 2, 5};
    for (int megabytes : sizes) {
      // Three repetitions, reporting the MINIMUM. The minimum is the cleanest estimate of the
      // underlying cost: every source of noise available on a developer machine -- GC, the OS
      // scheduler, a PostgreSQL container sharing cores with this JVM -- can only ADD time, never
      // remove it. A mean would report the machine's background load as if it were the database's.
      long best = Long.MAX_VALUE;
      List<Long> samples = new ArrayList<>();
      for (int repetition = 0; repetition < 3; repetition++) {
        long elapsed = writeOnce(transactionTemplate, megabytes);
        samples.add(elapsed);
        best = Math.min(best, elapsed);
      }
      report.append(
          String.format(
              "  %d MB -> %d ms best of %s (%.1f ms/MB)%n",
              megabytes, best, samples, best / (double) megabytes));
      assertTrue(best >= 0, "elapsed time must be measurable");
    }

    report.append(
        "  NOTE: this is the whole transaction, which is the honest figure -- the connection is\n"
            + "  held for all of it. Uqudo downloads and the Civil Registry call happen BEFORE the\n"
            + "  transaction opens (IdentityScanService), so no network I/O is inside this hold.\n"
            + "  Allocation and SHA-256 sit outside the timed region: they cost heap and CPU but\n"
            + "  hold no connection, and the connection is what AP-7 is about.\n"
            + "  UNDERSTATED, TWICE OVER: this container runs fsync=off, so no WAL flush is paid,\n"
            + "  and the database is on loopback, where a two-machine deployment moves the bytes\n"
            + "  over a wire first. The ms/MB rate is exactly what those two change, so treat it\n"
            + "  as a LOWER BOUND rather than a portable constant. A real journey also writes\n"
            + "  several artifact rows in one transaction, paying the fixed term more than once.\n");
    record(report.toString());
  }

  // ------------------------------------------- M3: inline OTP occupancy of a worker thread

  @Test
  @Order(3)
  void inlineOtpSendOccupiesOneWorkerForTheSumOfItsChannels() {
    // The measurement AP-7 gets most wrong today. Its third row says one-time codes are "delayed
    // rather than lost" by the dispatcher -- but OTPs never enter the outbox. They are sent inline,
    // sequentially, one per channel, on the request thread. So the cost of a slow gateway here is
    // WORKER OCCUPANCY, a different resource from the one AP-7 names, and it is measured by timing
    // the sends themselves rather than a queue.
    StringBuilder report = new StringBuilder("M3 INLINE OTP WORKER OCCUPANCY\n");

    for (long latency : new long[] {0L, 100L, 300L}) {
      SEND_LATENCY_MILLIS.set(latency);
      long elapsed = timed(() -> sendThreeChannelOtp());
      report.append(
          String.format(
              "  3 channels at %dms each -> %d ms of worker occupancy for ONE request%n",
              latency, elapsed));
      assertTrue(
          elapsed >= 3 * latency,
          "three channels at "
              + latency
              + "ms should occupy a worker for at least "
              + (3 * latency)
              + "ms, took "
              + elapsed
              + "ms");
    }
    SEND_LATENCY_MILLIS.set(0);

    // THE SENTENCE THIS ORIGINALLY PUBLISHED WAS IMPOSSIBLE: "three channels at Airtel's 15s read
    // timeout hold a worker for 45s". Two errors in one line. MessageSenderConfiguration REFUSES
    // `http` for any channel but SMS, so at most ONE channel can reach a real gateway; and Airtel's
    // worst case is connect 5s PLUS read 15s = 20s, not 15s.
    report.append(
        "  => in the SHIPPED configuration only SMS has a real gateway, so the worst case is one\n"
            + "     channel at Airtel's 5s connect + 15s read = 20s of occupancy for one of the\n"
            + "     100 workers. The 3x figures above are what that becomes IF WhatsApp and email\n"
            + "     ever gain real gateways: occupancy is then the sum across channels, on one\n"
            + "     thread, because the sends are sequential.\n");
    record(report.toString());
  }

  // ---------------------------------------------------------------------------- plumbing

  /**
   * One timed artifact write against a freshly seeded profile. Allocation and hashing happen before
   * the clock starts, because neither holds a connection.
   */
  private long writeOnce(TransactionTemplate transactionTemplate, int megabytes)
      throws SQLException {
    byte[] body = new byte[megabytes * 1024 * 1024];
    byte[] digest = sha256(body);
    UUID target = seedProfile(nextAccount());
    return timed(
        () -> transactionTemplate.executeWithoutResult(status -> insert(target, body, digest)));
  }

  private static String nextAccount() {
    int next = NEXT_ACCOUNT.getAndIncrement();
    if (next > ACCOUNT_RANGE_END) {
      throw new IllegalStateException(
          "this class's reserved account range is exhausted at "
              + ACCOUNT_RANGE_END
              + "; claim a new range in AbstractPostgresIntegrationTest's registry rather than"
              + " reusing another suite's");
    }
    return String.format("%010d", next);
  }

  /**
   * One send per channel, in a loop on this thread — the shape {@code ContactChannelsService} uses
   * for an OTP challenge. Driven through the port rather than through the HTTP endpoint on purpose:
   * the endpoint would also mint a profile, take row locks and write audit rows per call, none of
   * which is what this measures, and all of which would have to be subtracted back out again.
   */
  private void sendThreeChannelOtp() {
    for (MessageChannel channel : MessageChannel.values()) {
      messageSender.send(
          new OutboundMessage(
              UUID.randomUUID(),
              channel,
              destinationFor(channel),
              payloadFor(channel),
              Urgency.INTERACTIVE,
              null));
    }
  }

  private static String destinationFor(MessageChannel channel) {
    return channel == MessageChannel.EMAIL ? "measurement@example.invalid" : "+249900000466";
  }

  private static MessagePayload payloadFor(MessageChannel channel) {
    return switch (channel) {
      case SMS -> new SmsPayload("قيد القياس");
      case WHATSAPP ->
          new WhatsAppPayload(
              "measurement", "ar", List.of("000000"), WhatsAppTemplateCategory.AUTHENTICATION);
      case EMAIL -> new EmailPayload("قيد القياس", "قيد القياس", null);
    };
  }

  private void enqueueOne() {
    outboxRepository.enqueue(
        profileId, MessageChannel.SMS, "+249900000466", new SmsPayload("قيد القياس"));
  }

  private void insert(UUID target, byte[] body, byte[] digest) {
    jdbcTemplate.update(
        """
        INSERT INTO app.artifact_ref
          (cycle_id, profile_id, kind, storage_key, content_type, byte_size, sha256, state, body)
        VALUES (NULL, ?::uuid, 'salary_certificate', ?, 'application/pdf', ?, ?, 'committed', ?)
        """,
        target.toString(),
        "measurement/" + UUID.randomUUID(),
        (long) body.length,
        digest,
        body);
  }

  private static byte[] sha256(byte[] body) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(body);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static long timed(Runnable work) {
    long start = System.nanoTime();
    work.run();
    return Duration.ofNanos(System.nanoTime() - start).toMillis();
  }

  private static void record(String text) {
    try {
      Files.createDirectories(RESULTS.getParent());
      // One header per run. This file APPENDS and is the citation source for a document sent to a
      // bank; without a marker a second run without `clean` concatenates into one indistinguishable
      // block and nobody can tell which numbers came from which build.
      if (HEADER_WRITTEN.compareAndSet(false, true)) {
        Files.writeString(
            RESULTS,
            "=== capacity measurement run " + Instant.now() + " ===\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
      Files.writeString(
          RESULTS,
          text + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException("could not write " + RESULTS.toAbsolutePath(), e);
    }
    System.out.print(text);
  }

  /**
   * Minimal valid profile, built as {@code fru_migrator} — the same technique {@code
   * NotificationOutboxIntegrationTest} uses and for the same reason: an outbox row needs a real
   * {@code app.profile} and a real audit chain to append to, and no application code creates one
   * without walking the whole journey. Deliberately not a claim about how profiles are really made.
   */
  private static UUID seedProfile(String accountNumber) throws SQLException {
    UUID profileId = UUID.randomUUID();

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
          accountNumber);

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
    return profileId;
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
