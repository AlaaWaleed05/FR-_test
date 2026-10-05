package com.sfbank.bayanati;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

// S2-02: proves the exact gap S2-01 recorded — that DataSourceAutoConfiguration and
// FlywayAutoConfiguration, excluded from BackendApplicationTests to keep ./mvnw test
// Docker-independent, actually work end-to-end against a real PostgreSQL. Unlike that smoke
// test, this one does NOT exclude them: it boots the full context, letting Spring Boot's own
// Flyway auto-configuration run every migration, then queries information_schema to confirm
// the app/audit schemas landed. Tagged "integration" and excluded from ./mvnw test/verify by
// default (see pom.xml excludedGroups) — run with `./mvnw test -Pdb-integration-test`
// (requires Docker running). S2-03 added a second @Test on the same migrated context
// asserting the ref schema's seeded row counts and the two closed FKs. S3-09: container and
// dynamic properties moved to AbstractPostgresIntegrationTest — see its Javadoc for the
// singleton-container reuse pattern and how isolation across every @Tag("integration") class is
// kept without truncating anything.
@Tag("integration")
@SpringBootTest
class AppSchemaConnectivityIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void springBootFlywayMigratesAgainstRealPostgresAndAppAuditSchemasArePresent() {
    // The context loading at all, with DataSourceAutoConfiguration and FlywayAutoConfiguration
    // both enabled (see the class-level comment), is itself the primary proof. The queries
    // below additionally confirm the migrations actually ran and both schemas are populated.
    //
    // schema_name is filtered to app/audit here — ref is asserted separately below in
    // refSchemaSeedRowCountsMatchExpected(), which checks its actual seeded content rather
    // than just its presence.
    List<String> schemas =
        jdbcTemplate.queryForList(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN"
                + " ('app', 'audit') ORDER BY schema_name",
            String.class);
    assertEquals(List.of("app", "audit"), schemas);

    Integer profileTableCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = 'app' AND table_name = 'profile'",
            Integer.class);
    assertEquals(1, profileTableCount);

    Integer auditEventTableCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = 'audit' AND table_name = 'audit_event'",
            Integer.class);
    assertEquals(1, auditEventTableCount);

    Integer statusCodeSeedCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM app.status_code", Integer.class);
    assertEquals(9, statusCodeSeedCount);

    assertTrue(POSTGRES.isRunning());
  }

  // S2-03: guards the ref schema's seeded row counts (V0011–V0019) against a later edit
  // silently dropping a row or a whole list — found missing in review: nothing before this
  // asserted the counts the S2-03 session report proves by hand. Shares the same migrated
  // container/context as the test above (one Flyway run for the whole class). S2-07 extended
  // the map with the seventh list (country, V0022). S2-08 closed the country FK deferred at
  // S2-07 (V0025), for all three of country_of_residence/home_country/work_country. S2-09
  // added a fourth country FK (birth_country_fk, V0028) once field 22 moved to customer entry.
  @Test
  void refSchemaSeedRowCountsMatchExpected() {
    Map<String, Integer> expectedRowCounts =
        Map.of(
            "occupation", 135,
            "branch", 25,
            "admin_division", 151, // 1 country + 15 states + 135 localities
            "income_source", 9,
            "education_level", 7,
            "rejection_reason", 7,
            "country", 249);

    expectedRowCounts.forEach(
        (listCode, expectedCount) -> {
          Integer actualCount =
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM ref.reference_item WHERE list_code = ?",
                  Integer.class,
                  listCode);
          assertEquals(expectedCount, actualCount, "row count for list_code=" + listCode);
        });

    Integer occupationFkCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_constraint"
                + " WHERE conname IN ('occupation_fk', 'reason_code_fk',"
                + " 'country_of_residence_fk', 'home_country_fk', 'work_country_fk',"
                + " 'birth_country_fk')",
            Integer.class);
    assertEquals(6, occupationFkCount);
  }

  // S2-08: guards app.provenance_matrix_version's seed rows against a later edit silently
  // dropping one — the same lightweight-regression-guard reasoning as the FK count above. S2-09
  // added version 2 (field 22's source correction), so this now expects both rows by version.
  @Test
  void provenanceMatrixVersionIsSeeded() {
    Integer versionCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.provenance_matrix_version", Integer.class);
    assertEquals(2, versionCount);

    List<Integer> versions =
        jdbcTemplate.queryForList(
            "SELECT version FROM app.provenance_matrix_version ORDER BY version", Integer.class);
    assertEquals(List.of(1, 2), versions);
  }

  // S2-05: guards V0020/V0021 against a later edit silently dropping a seed row or a trigger
  // — found missing in review, the same gap S2-03 closed for its own migrations above. The
  // deferred-constraint-trigger behaviour is proven live with real psql sessions in the S2-05
  // session report (which also proved the four-eyes rule, removed by AD-013 at S8-27 -- read that
  // half of the report as history); this is a lightweight regression guard, not a
  // re-proof. The negative case runs on its own JDBC connection with autocommit off so the
  // illegal UPDATE's rejection (an immediate, non-deferred BEFORE trigger) can be asserted
  // and the connection rolled back without depending on a Spring-managed transaction.
  @Test
  void statusTransitionGuardIsWiredAndEnforced() {
    Integer transitionCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM app.status_transition", Integer.class);
    assertEquals(21, transitionCount);

    Integer triggerCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_trigger WHERE tgname IN"
                + " ('profile_status_transition_guard', 'profile_status_requires_history')"
                + " AND NOT tgisinternal",
            Integer.class);
    assertEquals(2, triggerCount);

    String profileId = UUID.randomUUID().toString();
    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            con -> {
              con.setAutoCommit(false);
              try (Statement st = con.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO app.profile (profile_id, branch_code, account_number, status,"
                        + " status_changed_at, last_activity_at) VALUES ('"
                        + profileId
                        + "'::uuid, '2', 'ACCT-JAVA-TEST', 'in_progress', clock_timestamp(),"
                        + " clock_timestamp())");
                try {
                  st.executeUpdate(
                      "UPDATE app.profile SET status = 'approved' WHERE profile_id = '"
                          + profileId
                          + "'::uuid");
                  fail("illegal transition in_progress -> approved should have been rejected");
                } catch (SQLException expected) {
                  assertTrue(
                      expected.getMessage().contains("illegal status transition"),
                      "unexpected error: " + expected.getMessage());
                }
              } finally {
                con.rollback();
                con.setAutoCommit(true);
              }
              return null;
            });
  }

  // S2-04: guards the audit seal export migrations (V0030-V0033) against a later edit silently
  // dropping the seal-chain snapshot table, its append-only triggers, or the fru_sealer role —
  // the same lightweight-regression-guard reasoning as the checks above. The seal's actual
  // behaviour (create/verify/tamper-detection, including the recompute-attack proof) is proven
  // live with real psql sessions in the S2-04 session report; this is a wiring check, not a
  // re-proof.
  //
  // Deliberately queries pg_class/pg_namespace, not information_schema.tables: this test's
  // jdbcTemplate connects as fru_app (see the class-level comment), and information_schema
  // filters to only the objects the current role can access. fru_app has NO grant at all on
  // audit_seal_chain (by design — see docs/sessions/2026-08-27-s2-04-audit-seal.md), so
  // information_schema.tables hides it from this very connection; querying it that way
  // failed here first, which is itself a live confirmation the zero-grant design works.
  @Test
  void auditSealTablesRolesAndTriggersAreWired() {
    Integer sealChainTableCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'audit' AND c.relname = 'audit_seal_chain'",
            Integer.class);
    assertEquals(1, sealChainTableCount);

    Integer sealerRoleCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_roles WHERE rolname = 'fru_sealer'", Integer.class);
    assertEquals(1, sealerRoleCount);

    Integer sealTriggerCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_trigger WHERE tgname IN"
                + " ('audit_seal_guard', 'audit_seal_no_truncate',"
                + " 'audit_seal_chain_immutable', 'audit_seal_chain_no_truncate')"
                + " AND NOT tgisinternal",
            Integer.class);
    assertEquals(4, sealTriggerCount);
  }

  // S3-10 (V0038): guards the search_en fix against a later edit reverting it to a plain,
  // unpopulated column — the defect the 2026-08-30 client-component research found (§8.2):
  // stage 4's SETTLED English substring search had nothing to match, since no seed migration
  // ever wrote search_en. This is a lightweight regression guard only; the actual live
  // before/after proof (search_en NULL pre-V0038, matching post-V0038) is run by hand against
  // the docker-compose Postgres via the flyway-maven-plugin and pasted into the S3-10 session
  // report, per CLAUDE.md's live-proof-vs-passing-test rule for schema-level guarantees.
  @Test
  void searchEnIsGeneratedFromLabelEnAndMatchesEnglishSubstring() {
    Map<String, Object> generation =
        jdbcTemplate.queryForMap(
            "SELECT is_generated, generation_expression FROM information_schema.columns"
                + " WHERE table_schema = 'ref' AND table_name = 'reference_item'"
                + " AND column_name = 'search_en'");
    assertEquals("ALWAYS", generation.get("is_generated"));
    assertTrue(String.valueOf(generation.get("generation_expression")).contains("ar_fold"));

    Integer trgmIndexCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'ref'"
                + " AND tablename = 'reference_item'"
                + " AND indexname = 'reference_item_search_en_trgm'",
            Integer.class);
    assertEquals(1, trgmIndexCount);

    // خياط ("Needle" in this bank's supplied English translation, V0014) is item_code 25 —
    // the same example the research report and this fix's session report both cite.
    String itemCode =
        jdbcTemplate.queryForObject(
            "SELECT item_code FROM ref.reference_item"
                + " WHERE list_code = 'occupation' AND search_en ILIKE '%needle%'",
            String.class);
    assertEquals("25", itemCode);
  }

  /**
   * V0070 / BL-132, as narrowed by V0072 (AD-022, S9-01). Two things, and the second is the one
   * that would fail silently.
   *
   * <p>First, that the ONE printed-form kind clears {@code artifact_ref_kind_check} and that {@code
   * printed_form_attributed}, which V0072 withdrew, is REFUSED by it — a straight regression guard
   * on the re-added constraint, in the shape S2-03 established for every other migration here. Both
   * kinds cleared it until V0072.
   *
   * <p>Second, and this is the load-bearing half: that a profile may hold MANY printed forms.
   * Wayfinder ticket 05 decision 2 says every print is its own artifact and reprinting is the
   * expected case, and the whole reason V0070's rows are profile-keyed with {@code cycle_id} NULL
   * is that V0008's {@code UNIQUE (cycle_id, kind)} would otherwise make the SECOND print of a
   * profile a constraint violation. That rests on PostgreSQL treating NULLs as distinct in a unique
   * constraint, which is the default but is not the only possible one — {@code NULLS NOT DISTINCT}
   * exists, and a later migration adding it to this table would turn every reprint into a 500.
   * Asserted against the real database rather than reasoned about, because no unit test can see it.
   *
   * <p>Runs on its own connection with autocommit off and rolls back, so it leaves no rows behind —
   * the same isolation the status-guard test above uses, and what {@code
   * AbstractPostgresIntegrationTest}'s shared container requires.
   */
  @Test
  void aProfileMayHoldManyPrintedFormsBecauseTheUniqueConstraintTreatsNullCyclesAsDistinct() {
    String profileId = UUID.randomUUID().toString();
    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            con -> {
              con.setAutoCommit(false);
              try (Statement st = con.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO app.profile (profile_id, branch_code, account_number, status,"
                        + " status_changed_at, last_activity_at) VALUES ('"
                        + profileId
                        + "'::uuid, '2', 'ACCT-PRINT-TEST', 'in_progress', clock_timestamp(),"
                        + " clock_timestamp())");

                // ONE kind since AD-022 (S9-01) and V0072; there were two until then. Twice:
                // the first insert proves the kind clears the CHECK, the second proves a reprint
                // is not a uniqueness violation.
                for (int print = 1; print <= 2; print++) {
                  st.executeUpdate(insertPrintedForm(profileId, "printed_form"));
                }

                // The narrowed CHECK actually refuses the withdrawn kind. Asserted against the
                // real constraint, because V0072's whole risk is that the in-code constant and the
                // database drift apart -- and a savepoint so the refusal does not poison the
                // transaction this test still has assertions left to run in.
                st.executeUpdate("SAVEPOINT before_withdrawn_kind");
                try {
                  st.executeUpdate(insertPrintedForm(profileId, "printed_form_attributed"));
                  fail("V0072 must refuse the withdrawn printed_form_attributed kind");
                } catch (java.sql.SQLException refused) {
                  assertTrue(
                      String.valueOf(refused.getMessage()).contains("artifact_ref_kind_check"),
                      "refused by the kind CHECK, not by something else: " + refused.getMessage());
                }
                st.executeUpdate("ROLLBACK TO SAVEPOINT before_withdrawn_kind");

                try (var rs =
                    st.executeQuery(
                        "SELECT count(*) FROM app.artifact_ref WHERE profile_id = '"
                            + profileId
                            + "'::uuid")) {
                  assertTrue(rs.next());
                  assertEquals(
                      2, rs.getInt(1), "two prints of one profile: the one form, printed twice");
                }
              } finally {
                con.rollback();
                con.setAutoCommit(true);
              }
              return null;
            });
  }

  private static String insertPrintedForm(String profileId, String kind) {
    return "INSERT INTO app.artifact_ref (profile_id, cycle_id, kind, storage_key, content_type,"
        + " byte_size, sha256, body, state) VALUES ('"
        + profileId
        + "'::uuid, NULL, '"
        + kind
        + "', 'printed-form/"
        + UUID.randomUUID()
        + "', 'application/pdf', 4, sha256('%PDF'::bytea), '%PDF'::bytea, 'committed')";
  }
}
