package com.sfbank.bayanati.audit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The statement's shape and its fail-closed behaviour, against a mocked JdbcTemplate.
 *
 * <p>This is deliberately a unit test and not only an integration one: AccountCheckIntegrationTest
 * proves this class against a real PostgreSQL, but that test is @Tag("integration") and excluded
 * from ./mvnw verify, so without these the writer's lines would sit uncovered in the coverage
 * bundle and the 80% gate would be measuring less than it appears to.
 *
 * <p>S3-06: {@code append} now returns the generated {@code audit_event_id} (needed by {@code
 * app.profile_status_history}'s foreign key), so the underlying call is {@code
 * JdbcTemplate.query(sql, args, argTypes, RowMapper)} rather than {@code update} — the tests below
 * mock and assert against {@code query}.
 */
class JdbcAuditEventWriterTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final JdbcAuditEventWriter writer = new JdbcAuditEventWriter(jdbcTemplate);

  private static AuditEvent event(UUID profileId, UUID sessionId, UUID requestId) {
    return new AuditEvent(
        "system",
        "account_check",
        "account_check_attempted",
        "customer",
        null,
        profileId,
        sessionId,
        requestId,
        "{\"branch\":\"16\"}");
  }

  @SuppressWarnings("unchecked")
  private static void stubGeneratedId(JdbcTemplate jdbcTemplate, long generatedId) {
    when(jdbcTemplate.query(
            anyString(), any(Object[].class), any(int[].class), any(RowMapper.class)))
        .thenReturn(List.of(generatedId));
  }

  @Test
  void theEventIsInsertedWithItsChainResolvedInTheStatementAndReturnsTheGeneratedId() {
    stubGeneratedId(jdbcTemplate, 42L);
    UUID requestId = UUID.randomUUID();

    long id = writer.append(event(null, null, requestId));

    assertEquals(42L, id);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate)
        .query(sql.capture(), args.capture(), any(int[].class), any(RowMapper.class));

    assertTrue(sql.getValue().contains("INSERT INTO audit.audit_event"), sql.getValue());
    assertTrue(
        sql.getValue().contains("FROM audit.audit_chain"),
        "the chain must be resolved in the statement, so a missing chain inserts no row");
    assertTrue(
        sql.getValue().contains("RETURNING audit_event_id"),
        "the generated id must come back from the same statement");

    Object[] bound = args.getValue();
    assertEquals("account_check_attempted", bound[0]);
    assertEquals("customer", bound[1]);
    assertNull(bound[2], "actor id");
    assertNull(bound[3], "profile id");
    assertNull(bound[4], "session id");
    assertEquals(requestId.toString(), bound[5]);
    assertEquals("{\"branch\":\"16\"}", bound[6]);
    assertEquals("system", bound[7]);
    assertEquals("account_check", bound[8]);
  }

  @Test
  void uuidsAreBoundAsTextAgainstTheStatementsExplicitCasts() {
    stubGeneratedId(jdbcTemplate, 1L);
    UUID profileId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    writer.append(event(profileId, sessionId, requestId));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate)
        .query(sql.capture(), args.capture(), any(int[].class), any(RowMapper.class));

    assertTrue(sql.getValue().contains("?::uuid"), "uuid parameters need an explicit cast");
    assertEquals(profileId.toString(), args.getValue()[3]);
    assertEquals(sessionId.toString(), args.getValue()[4]);
    assertEquals(requestId.toString(), args.getValue()[5]);
  }

  /**
   * The whole statement, asserted verbatim.
   *
   * <p>The other tests here check that individual fragments are present, which would survive a
   * reordering of the column list that no longer lines up with the argument array — and the only
   * thing that proves the correspondence against the real schema is AccountCheckIntegrationTest,
   * which is @Tag("integration") and excluded from ./mvnw verify. So the default gate could
   * otherwise go green over a writer binding parameters to the wrong columns. Pinning the exact
   * text means any reordering fails here, loudly, and has to be a deliberate edit to both sides.
   */
  @Test
  void theStatementIsExactlyThis() {
    stubGeneratedId(jdbcTemplate, 1L);

    writer.append(event(null, null, UUID.randomUUID()));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .query(sql.capture(), any(Object[].class), any(int[].class), any(RowMapper.class));

    assertEquals(
        """
        INSERT INTO audit.audit_event
          (chain_id, seq, occurred_at, event_type, actor_kind, actor_id,
           profile_id, session_id, request_id, payload_json,
           prev_hash, content_hash, row_hash)
        SELECT c.chain_id, 0, clock_timestamp(), ?::text, ?::text, ?::text,
               ?::uuid, ?::uuid, ?::uuid, ?::text,
               ''::bytea, ''::bytea, ''::bytea
          FROM audit.audit_chain c
         WHERE c.chain_kind = ?::text AND c.subject_id = ?::text
        RETURNING audit_event_id
        """,
        sql.getValue());
  }

  @Test
  void theTriggerOwnedColumnsAreNeverBoundAsParameters() {
    stubGeneratedId(jdbcTemplate, 1L);

    writer.append(event(null, null, UUID.randomUUID()));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .query(sql.capture(), any(Object[].class), any(int[].class), any(RowMapper.class));

    // seq / occurred_at / the three hashes are computed by audit.chain_append(); the literals in
    // the statement exist only to satisfy NOT NULL and must never become application inputs.
    assertTrue(sql.getValue().contains("''::bytea, ''::bytea, ''::bytea"), sql.getValue());
    assertTrue(sql.getValue().contains("clock_timestamp()"), sql.getValue());
  }

  @SuppressWarnings("unchecked")
  @Test
  void aMissingChainFailsRatherThanLosingTheEvent() {
    when(jdbcTemplate.query(
            anyString(), any(Object[].class), any(int[].class), any(RowMapper.class)))
        .thenReturn(List.of());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> writer.append(event(null, null, UUID.randomUUID())));

    assertTrue(thrown.getMessage().contains("account_check_attempted"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("system"), thrown.getMessage());
  }
}
