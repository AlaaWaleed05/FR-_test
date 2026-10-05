package com.sfbank.bayanati;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * S3-09: the Testcontainers "singleton container" pattern (java.testcontainers.org, "Manual
 * container lifecycle control") — ONE PostgreSQL 18 container for the whole Surefire JVM, started
 * here in a static initializer and never explicitly stopped: Testcontainers' Ryuk sidecar (or, on
 * JVM shutdown before Ryuk's own housekeeping runs, its {@code ResourceReaper} shutdown hook) reaps
 * it when the JVM exits. This is a genuinely different reliance than the five per-class
 * {@code @Container} fields this replaces had: those were stopped deterministically by the
 * {@code @Testcontainers} JUnit extension's {@code afterAll} once a class's own tests finished,
 * with Ryuk only as its backstop; a JVM-scoped singleton has no per-class {@code afterAll} to do
 * that, so Ryuk (or the shutdown hook) is the only mechanism here, not a backstop to one. Verified
 * live: {@code docker ps -a --filter ancestor=postgres:18} shows nothing left running after a full
 * Surefire run exits. Chosen over {@code withReuse(true)} + {@code testcontainers.reuse.enable}:
 * that mechanism persists a container ACROSS separate Maven invocations via a machine-wide opt-in
 * file, which would leave containers running after unrelated future runs, on this or any other
 * project, until manually cleaned up. A JVM-scoped singleton fully addresses the observed problem —
 * repeated container starts within one Surefire run, one of which cold-started to 240s after many
 * earlier starts in the same long dev session (see the S3-07 session report §6.1) — without that
 * cross-invocation risk.
 *
 * <p>Every {@code @Tag("integration")} class extends this instead of declaring its own
 * {@code @Container}/{@code @DynamicPropertySource} pair. Spring's {@code @DynamicPropertySource}
 * support scans the full class hierarchy, so {@link #sharedDatabaseProperties} applies to every
 * subclass automatically; a subclass is still free to add its own {@code @DynamicPropertySource}
 * method for properties only it needs (e.g. {@code AccountCheckIntegrationTest}'s core-banking stub
 * fixtures) — both contribute to the same dynamic property source.
 *
 * <p><strong>Isolation — no truncation.</strong> A per-class {@code TRUNCATE} was tried first and
 * rejected: {@code app.profile}'s only mutation-blocking constraint is its {@code UNIQUE
 * (account_number)} (account-only since V0061/BL-032), but {@code app.profile_status_history} —
 * reachable by {@code CASCADE} off {@code app.profile}, since every other mutable {@code app} table
 * FKs to it directly or transitively — carries its OWN append-only guard (V0010: {@code
 * profile_status_history_ immutable}/{@code profile_status_history_no_truncate}, triggers that
 * reject the operation even for the owning role, "the same defence-in-depth reasoning as audit
 * Layer 2" per that migration's own comment). {@code TRUNCATE ... CASCADE} failed live against it:
 * {@code "ERROR: app.profile_status_history is append-only; TRUNCATE is not permitted"}. V0010 also
 * documents {@code app.profile} itself as "never deleted" by design (90-day retention nulls PII in
 * place instead) — so a profile row, once created, is not actually meant to be cleared between test
 * classes any more than an audit row is. Weakening either guard to make reuse convenient is exactly
 * what this task rules out.
 *
 * <p>The actual isolation mechanism is namespace partitioning, not cleanup: every one of these
 * {@code @Tag("integration")} classes already gives its fixtures a disjoint {@code account_number}
 * range (they were each written against their own throwaway container, so no class could ever have
 * collided with another's data before S3-09 either — sharing one container only makes that existing
 * property load-bearing instead of incidental). Confirmed disjoint by inspection:
 *
 * <ul>
 *   <li>{@code AccountCheckIntegrationTest} — branch 16: 0000000001, 0000000002, 0000009999,
 *       0000000501, 0000000502, (S4-06, BL-021) 0000000003, and (BL-032) 0000000004 — the last is
 *       also checked under branch 22, which is the point of that test.
 *   <li>{@code AppSchemaConnectivityIntegrationTest} — branch 2, account "ACCT-JAVA-TEST", and the
 *       one test that writes it rolls the transaction back — never actually committed.
 *   <li>{@code ContactChannelsIntegrationTest} — branch 16: 0000000101–0000000112 (0000000111 is
 *       re-entered under branch 22 and 0000000112 is probed under branch 99, by design — BL-032).
 *   <li>{@code OtpVerificationIntegrationTest} — branch 16: an {@code AtomicInteger} counter
 *       starting at 200, formatted {@code "00000002%02d"} (11 characters — a different length than
 *       every other class's 10-character account numbers, so even a numeric near-miss cannot
 *       collide against a {@code text} column compared by exact value).
 *   <li>{@code NotificationOutboxIntegrationTest} — branch 16, account 0000000099, seeded once per
 *       class via its own {@code @BeforeEach} + {@link java.util.concurrent.atomic.AtomicBoolean}
 *       guard (unaffected by this class — see its own Javadoc). Its S6-04 drain test additionally
 *       pushes {@code next_attempt_at} an hour out for EVERY still-pending row in the table before
 *       it runs, so that one scheduler tick has a deterministic set of rows to claim. That is the
 *       same thing every enqueuing class already does to its own rows, applied table-wide, so it
 *       takes nothing away from another class that was relying on the convention below.
 *   <li>{@code DataEntryIntegrationTest} (S3-11/S3-12/S4-04) — branch 16: 0000000301–0000000307 for
 *       the stages 3-6 proofs, 0000000320–0000000322 for the BL-016 (E.164) proofs, 0000000330 for
 *       the S3-12 stage 7 proof, 0000000331–0000000333 for the S4-04 version-pinning proofs (which
 *       also insert, then fully delete, a throwaway {@code occupation} version 2 and restore its
 *       {@code is_current} pointer — see that class's own Javadoc).
 *   <li>{@code IdentityScanIntegrationTest} (S3-12) — branch 16: 0000000401–0000000410, plus
 *       0000000531 for BL-034's dropped-acknowledgement retry proof (the original ten were all
 *       spoken for, and the next free block after SalaryCertificate's 0000000530 is 0000000531),
 *       plus 0000000532–0000000533 for S5-11's Stage 9 resume-read and already-ok-retry proofs,
 *       plus 0000000534 for BL-037's SCAN_REJECTED proof, plus 0000000535–0000000536 for the
 *       registry-retry race proofs, plus 0000000537-0000000541 for BL-039's single-use-session,
 *       refused-wrong-number and lifetime-token-cap proofs. (This entry stopped at 534 until
 *       BL-041, which claimed a range starting at 535 on the strength of it and collided with the
 *       race proof's 0000000536 — caught by a real gate failure, not review. Grep the sources, not
 *       just this list.)
 *   <li>{@code LivenessIntegrationTest} (S3-13) — branch 16: 0000000411–0000000420.
 *   <li>{@code SignatureIntegrationTest} (S3-13) — branch 16: 0000000421–0000000426.
 *   <li>{@code SubmissionIntegrationTest} (S3-13/S4-04) — branch 16: 0000000430–0000000440, plus
 *       0000000496 for the S4-04 {@code ref.profile_reference_version} proof. Also enqueues to
 *       {@code app.notification_outbox} and backdates {@code next_attempt_at} an hour into the
 *       future immediately after each enqueue, the same convention {@code
 *       NotificationOutboxIntegrationTest} established, for the same reason (see below).
 *   <li>{@code OperatorProfileListIntegrationTest} (S4-01) — branch 16: 0000000441–0000000445.
 *       Every fixture's phone number carries a test-specific prefix ({@code +249911001} or {@code
 *       +249911002} — deliberately NOT {@code +2499000...}, which collides with {@code
 *       ContactChannelsIntegrationTest}'s own phone numbers) used as the list search term, since
 *       branch/account-number scoping alone is not enough to isolate one test method's row count
 *       from every other integration class's rows sharing branch 16 in the same container.
 *   <li>{@code OperatorProfileViewIntegrationTest} (S4-01) — branch 16: 0000000446–0000000450, plus
 *       0000000590–0000000595 added at S8-29. Also enqueues to {@code app.notification_outbox} (via
 *       {@code submission}) and backdates {@code next_attempt_at} inside its own {@code submit()}
 *       helper, same convention as {@code SubmissionIntegrationTest}. The S8-29 block is a separate
 *       range rather than a continuation because 0000000451 onward was already {@code
 *       OperatorReviewIntegrationTest}'s; it starts at 590, leaving a gap after {@code
 *       OperatorImageIntegrationTest}'s 585. One profile there (0000000590) has its {@code
 *       salary_certificate} artifact driven to {@code state='purged'} directly, to pin BL-142's
 *       fall-through — profile-keyed with a NULL {@code cycle_id}, so it cannot collide with any
 *       cycle-keyed row under {@code UNIQUE (cycle_id, kind)}.
 *   <li>{@code OperatorReviewIntegrationTest} (S4-01) — branch 16: 0000000451–0000000465
 *       (0000000463 claimed at S9-02 for BL-154's withdrawn-reason proof). Also enqueues to {@code
 *       app.notification_outbox} (via {@code submission}, and via every successful approve/reject)
 *       and backdates {@code next_attempt_at} after every one of those writes — its own {@code
 *       submit()} helper backdates immediately, and every successful approve/reject call site
 *       backdates again right after, since each is a separate enqueue the first backdate cannot
 *       have covered yet.
 *   <li>{@code CapacityMeasurementTest} (S9-08) — branch 16: 0000000466–0000000480, the range this
 *       registry had recorded as free after {@code ManualCompletionIntegrationTest} (S4-02) was
 *       deleted by AD-022 at S9-01. Taken here exactly as that entry invited. It is
 *       {@code @Tag("load")} and so runs in NO gate — only under {@code -Pload-test} — but it
 *       claims the range anyway: a range is claimed against the REGISTRY, not against whichever
 *       suites happen to run together, or the next session to read this list would reissue it. It
 *       seeds one profile per artifact write, because V0059's partial unique index allows a profile
 *       exactly one {@code salary_certificate}, so the range is consumed a profile at a time rather
 *       than a test at a time. <strong>It is the one enqueuing class that does NOT backdate {@code
 *       next_attempt_at}</strong>, against the rule stated further down this Javadoc: it drains
 *       every row it enqueues inside the same test, because draining is the thing it measures. That
 *       is safe only while {@code @Tag("load")} keeps it out of every gate, and therefore out of
 *       any JVM that also runs an enqueuing class. If that tag is ever removed, this class has to
 *       adopt the backdating convention in the same change.
 *   <li>{@code ProfileExportIntegrationTest} (S4-02) — branch 16: 0000000481–0000000495.
 *   <li>{@code ReferenceDocumentPublisherIntegrationTest} (S4-03) — not branch/account-number
 *       scoped (it never creates a profile). Claims schema {@code ref} instead: two throwaway,
 *       never-{@code is_current} {@code list_code} values — {@code s403_root_trigger_test} in both
 *       {@code ref.reference_list} and {@code ref.reference_list_version}, {@code
 *       s403_root_fk_test} in {@code ref.reference_list} only (its {@code reference_list_version}
 *       row is the one rejected under test) — plus a throwaway {@code s403_item_count_mismatch}
 *       list and a transactional-rollback probe on {@code occupation/1} that leaves no trace once
 *       its outer transaction rolls back. It also republishes the seven real, shared reference-list
 *       documents (occupation/branch/admin_division/income_source/education_level/
 *       rejection_reason/country) via {@code ReferenceDocumentPublisher} — safe because publishing
 *       is idempotent (byte-identical output from unchanged {@code ref.reference_item} rows) and no
 *       other class in this suite reads {@code ref.reference_list_document} or {@code
 *       ref.reference_list_version.content_hash}.
 *   <li>{@code ReferenceControllerIntegrationTest} (S4-04) — not branch/account-number scoped (it
 *       creates no profile). Republishes the shared {@code occupation/1} document via {@code
 *       ReferenceDocumentPublisher} before each test, the same idempotent-republish reasoning
 *       {@code ReferenceDocumentPublisherIntegrationTest} documents for itself — it never changes
 *       {@code is_current} or any row content, only overwrites {@code
 *       ref.reference_list_document}/{@code content_hash} with byte-identical output.
 *   <li>{@code ArtifactStorageIntegrationTest} (S5-06) — branch 16: 0000000497–0000000500. Two of
 *       its profiles are transitioned to {@code abandoned} with a backdated {@code
 *       last_activity_at} and then genuinely purged by {@code app.purge_abandoned_artifacts()} —
 *       harmless to every other class, since none reads these two profiles or their artifacts.
 *   <li>{@code auth.OperatorAuthenticationIntegrationTest} (S4-05) — branch 16:
 *       0000000503–0000000520 (NOT 0000000501/0000000502 — those belong to {@code
 *       AccountCheckIntegrationTest}, above). {@code app.operator_user.username} is a separate
 *       namespace (globally unique, not branch/account-scoped) — every seeded account uses the
 *       {@code s405.*} prefix, unique to this class.
 *   <li>{@code SalaryCertificateIntegrationTest} (S4-06) — branch 16: 0000000521–0000000530.
 *   <li>{@code DeviceLessReentrySupersessionIntegrationTest} (BL-041/AD-008) — branch 16:
 *       0000000550–0000000560. Starts at 550, leaving a deliberate gap after {@code
 *       SalaryCertificateIntegrationTest}'s 530 and {@code IdentityScanIntegrationTest}'s
 *       out-of-order 531–536. Every account in this class is entered TWICE — the second Stage 1b
 *       POST is the device-less re-entry under test — so an account here carries two sessions'
 *       audit events and, after the rescan proofs, more than one identity cycle. A class that
 *       counts rows ACROSS a profile's cycles (as {@code portraitRegistryArtifactCount} does) will
 *       therefore see more than one row per kind if its range ever overlaps this one.
 *   <li>{@code OperatorImageIntegrationTest} (BL-075, extended by BL-136) — branch 16:
 *       0000000570–0000000585. Starts at 570, leaving a gap after {@code
 *       DeviceLessReentrySupersessionIntegrationTest}'s 560. Several of its profiles carry EXTRA
 *       artifact rows this class seeds directly (a byte-less capture frame, a {@code doc_back},
 *       {@code salary_certificate} rows declaring both {@code image/jpeg} and {@code
 *       application/pdf}, a {@code doc_front} falsely declaring {@code application/pdf}, and rows
 *       in the {@code superseded} and {@code purged} states), all profile-keyed with a NULL {@code
 *       cycle_id} so they cannot collide with cycle-keyed rows under {@code UNIQUE (cycle_id,
 *       kind)}. One profile (0000000580) has its {@code sha256} deliberately corrupted and is never
 *       readable again. 583–585 were added at S8-24; 581 and 582 were already in use further down
 *       the class, which is why the new three do not continue from 582.
 *   <li>{@code printedform.PrintedFormIntegrationTest} (BL-132) — branch 16: 0000000610–0000000626
 *       (extended by one at S9-01: 610–624 were all taken and the one-form print test needed a
 *       clean profile; extended again at S9-03 for the app.profile_field_edit read, 0000000626 —
 *       note that 0000000631 is NOT free despite the gap at 626–629, it belongs to the block
 *       beginning 0000000630 below, which is exactly the mistake this list exists to prevent).
 *       Several of its profiles carry one or more {@code printed_form} rows (there was a second
 *       kind, {@code printed_form_attributed}, until AD-022 withdrew it at S9-01/V0072),
 *       profile-keyed with a NULL {@code cycle_id} and DELIBERATELY not unique per profile — a
 *       reprint is a second row, which is the point of V0070. A class counting rows of those kinds
 *       across a profile must expect more than one. 0000000616 is APPROVED by this class, and
 *       0000000615 is left at {@code in_progress} on purpose (the refused-status case), so neither
 *       is a clean submitted profile for anyone else. 0000000624 carries a seeded {@code
 *       salary_certificate} PDF. This class also inserts provenance-matrix version 3 into {@code
 *       app.provenance_matrix_version} and DELETES it again in a {@code finally}: that is a seed
 *       table whose exact row count {@code AppSchemaConnectivityIntegrationTest} asserts, so the
 *       row must not outlive the method. 0000000624 carries a seeded {@code salary_certificate}
 *       PDF, and this class also INSERTS provenance-matrix version 3 into {@code
 *       app.provenance_matrix_version} — a seed table {@code AppSchemaConnectivityIntegrationTest}
 *       counts rows of, so that class asserts its own two seeded versions by value rather than by
 *       an unscoped count.
 *   <li>{@code operator.FieldEditIntegrationTest} (S9-02, BL-135) — branch 16:
 *       0000000630–0000000640. Every profile is carried through stages 3-6 as well as the
 *       scan/liveness chain, because per-field editing reads and writes {@code
 *       app.profile_customer_data} columns the other operator suites leave NULL. 0000000631's home
 *       address is deliberately in Egypt (fields 36/37 free text) and 0000000637 is APPROVED by
 *       this class, so neither is a clean submitted Sudan profile for anyone else. 0000000633,
 *       0000000634, 0000000635 and 0000000640 gain {@code app.profile_field_edit} rows, which makes
 *       those four {@code manual} through {@code app.derived_provenance()} — a class asserting an
 *       unscoped count of digital profiles would see them, so scope by {@code profile_id} as every
 *       class here already does. The rest gain none.
 * </ul>
 *
 * Every assertion across all these classes already reads state scoped to one test's own {@code
 * profile_id} (or a delta against a count taken earlier in the same test) — never an unscoped
 * whole-table count — so a shared container accumulating other classes' profiles across one run
 * changes nothing a test observes. A new integration test class MUST claim its own unused
 * branch/account-number range (extend the list above) rather than rely on tests running in any
 * particular order.
 *
 * <p>Disjoint keys are not the whole contract, though: {@code
 * JdbcNotificationOutboxRepository.claimOnePending()} runs an unscoped {@code SELECT ... WHERE
 * state = 'pending' AND next_attempt_at <= clock_timestamp() ... LIMIT 1} with no {@code
 * profile_id} predicate — a row any class enqueues without pushing {@code next_attempt_at} into the
 * future is claimable by whichever test happens to poll next, regardless of which class enqueued it
 * or what account range it used. {@code NotificationOutboxIntegrationTest}'s own {@code Order(5)}
 * test deliberately backdates {@code next_attempt_at} an hour into the future specifically to stay
 * unclaimable by an earlier-ordered test in the same class — see that test's own comment. {@code
 * SubmissionIntegrationTest} (S3-13) is the second writer and follows the same convention (see its
 * own class Javadoc). Any future integration class enqueuing a notification MUST do the same, even
 * while otherwise following the disjoint-range rule above; a claimable row left behind is a
 * cross-class collision no account-number partitioning prevents.
 *
 * <p>This is genuinely still per-invocation isolation, not merely per-class: {@link #POSTGRES} is
 * JVM-scoped, so two separate {@code ./mvnw test -Pdb-integration-test} invocations each start
 * their own fresh, empty container — nothing carries between runs.
 */
public abstract class AbstractPostgresIntegrationTest {

  // Not real secrets: throwaway credentials for one ephemeral, JVM-lifetime container. Same
  // style as .env.example's "changeme_local_dev_only".
  protected static final String FRU_MIGRATOR_PASSWORD = "testonly_migrator_pw";
  protected static final String FRU_APP_PASSWORD = "testonly_app_pw";
  protected static final String FRU_SEALER_PASSWORD = "testonly_sealer_pw";

  // Reuses the exact same init script docker-compose.yml mounts, so there is one source of truth
  // for how fru_migrator gets created — see db/init/01-create-migrator.sh.
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("fru")
          .withEnv("POSTGRES_INITDB_ARGS", "--locale-provider=icu --icu-locale=ar")
          .withEnv("FRU_MIGRATOR_PASSWORD", FRU_MIGRATOR_PASSWORD)
          // S9-08: max_connections raised from the postgres:18 default of 100, and ONLY because
          // this container is shared in a way a deployment never is. application.properties now
          // sets an explicit, fixed-size Hikari pool of 20 (see its "Capacity limits" block), and a
          // fixed-size pool opens all 20 eagerly. One deployment means one pool and 20 connections
          // against the bank's 100. This JVM holds SEVERAL Spring contexts at once — Spring's
          // context cache keys on configuration, so every distinct combination of
          // @MockitoSpyBean, @DynamicPropertySource, addFilters and webEnvironment is its own
          // context with its own 20-connection pool.
          //
          // COUNTED, not estimated, at S9-08: the @Tag("integration") classes group into TEN
          // distinct context keys (the largest groups being plain @SpringBootTest+MockMvc, the
          // three that spy MessageSender, and the five that use addFilters=false with that spy),
          // plus this class's own RANDOM_PORT context. Surefire runs forkCount=1/reuseForks=true
          // and nothing here is @DirtiesContext, so all ten coexist: 10 x 20 = 200 of 300. The
          // margin is five further contexts. Spring's context-cache ceiling is 32, so 32 x 20 =
          // 640 is the worst case this number does NOT cover — if a future session adds contexts
          // freely and sees connection failures, this is the line to raise, and the arithmetic to
          // redo rather than guess at.
          //
          // fsync=off IS NOT OURS AND MUST BE KEPT. PostgreSQLContainer's own constructor calls
          // setCommand("postgres", "-c", "fsync=off"), and withCommand REPLACES that rather than
          // adding to it — so passing max_connections alone silently turns fsync back ON for the
          // whole suite. Found at review; every test still passed, which is why it is spelt out
          // here: the gate cannot see this, it only gets slower.
          //
          // Raising the container is deliberately preferred to shrinking the pool for tests: the
          // integration suite should exercise the pool the application actually ships with, and
          // CapacityConfigurationTest asserts exactly that. A test-only pool override would make
          // that assertion vacuous — it would prove the override, not the configuration.
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300")
          .withCopyToContainer(
              MountableFile.forHostPath("../db/init/01-create-migrator.sh", 0755),
              "/docker-entrypoint-initdb.d/01-create-migrator.sh");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void sharedDatabaseProperties(DynamicPropertyRegistry registry) {
    String jdbcUrl = jdbcUrl();

    // spring.datasource.*: the application's runtime connection, as fru_app (owns nothing).
    registry.add("spring.datasource.url", () -> jdbcUrl);
    registry.add("spring.datasource.username", () -> "fru_app");
    registry.add("spring.datasource.password", () -> FRU_APP_PASSWORD);

    // spring.flyway.*: a distinct connection, as fru_migrator (owns everything) — mirrors
    // application.properties exactly.
    registry.add("spring.flyway.url", () -> jdbcUrl);
    registry.add("spring.flyway.user", () -> "fru_migrator");
    registry.add("spring.flyway.password", () -> FRU_MIGRATOR_PASSWORD);
    registry.add("spring.flyway.placeholders[fru_app_password]", () -> FRU_APP_PASSWORD);
    registry.add("spring.flyway.placeholders[fru_sealer_password]", () -> FRU_SEALER_PASSWORD);

    // CoreBankingClientConfiguration and MessageSenderConfiguration deliberately have no
    // default, so every context states its own choice or fails at startup.
    registry.add("fru.core-banking.client", () -> "stub");
    registry.add("fru.messaging.sms.provider", () -> "stub");
    registry.add("fru.messaging.whatsapp.provider", () -> "stub");
    registry.add("fru.messaging.email.provider", () -> "stub");
    registry.add("fru.uqudo.client", () -> "stub");
    registry.add("fru.civil-registry.client", () -> "stub");

    // S6-04: push OutboxDispatchScheduler's timer past the end of any test run. It must NEVER
    // fire inside this JVM. Its drain calls the same unscoped claimOnePending() poll described
    // two paragraphs up in this class's Javadoc, so a background tick would silently claim rows
    // belonging to whichever integration class enqueued them -- exactly the cross-class collision
    // the "backdate next_attempt_at an hour" convention exists to prevent, except arriving from a
    // thread no test controls. Postponed rather than switched off with a conditional bean, for
    // two reasons: the @Scheduled task stays REGISTERED, so
    // NotificationOutboxIntegrationTest.theScheduledTaskIsActuallyRegistered can prove the wiring
    // is real; and every cached Spring context in the run is covered, not only the one class that
    // exercises the drain. The drain test triggers pollOutbox() directly instead of waiting on a
    // clock.
    registry.add("fru.notification.outbox.poll-initial-delay", () -> "1h");
    registry.add("fru.notification.outbox.poll-interval", () -> "1h");
  }

  protected static String jdbcUrl() {
    return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/fru";
  }
}
