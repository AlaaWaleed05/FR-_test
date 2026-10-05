package com.sfbank.bayanati.profile.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * The statement shapes and parameter bindings, against a mocked JdbcTemplate — mirrors {@code
 * JdbcAuditEventWriterTest}'s reasoning: {@code ContactChannelsIntegrationTest} proves this class
 * against a real PostgreSQL but is {@code @Tag("integration")} and excluded from {@code ./mvnw
 * verify}, so without these unit tests this class's lines would sit uncovered in the coverage
 * bundle the 80% gate measures.
 */
class JdbcProfileRepositoryTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final JdbcProfileRepository repository = new JdbcProfileRepository(jdbcTemplate);

  @Test
  void ensureAuditChainCallsTheSecurityDefinerFunctionWithTheProfileId() {
    UUID profileId = UUID.randomUUID();

    repository.ensureAuditChain(profileId);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    verify(jdbcTemplate).query(sql.capture(), setter.capture(), any(ResultSetExtractor.class));
    assertTrue(sql.getValue().contains("audit.ensure_profile_chain"), sql.getValue());
  }

  @Test
  void insertProfileWritesTheProfileRowAndItsFirstStatusHistoryRow() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-29T12:00:00Z");

    repository.insertProfile(profileId, "0000000001", now, 42L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(sql.capture(), args.capture(), any(int[].class));

    String profileSql = sql.getAllValues().get(0);
    Object[] profileArgs = args.getAllValues().get(0);
    assertTrue(profileSql.contains("INSERT INTO app.profile"), profileSql);
    assertTrue(profileSql.contains("'in_progress'"), profileSql);
    assertEquals(profileId.toString(), profileArgs[0]);
    
    assertEquals("0000000001", profileArgs[2]);

    String historySql = sql.getAllValues().get(1);
    Object[] historyArgs = args.getAllValues().get(1);
    assertTrue(historySql.contains("INSERT INTO app.profile_status_history"), historySql);
    assertTrue(historySql.contains("NULL, 'in_progress', 'customer'"), historySql);
    assertEquals(profileId.toString(), historyArgs[0]);
    assertEquals(42L, historyArgs[1]);
  }

  @Test
  void insertContactDetailsWritesPhoneAndEmailOnly() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-29T12:00:00Z");

    repository.insertContactDetails(profileId, "+249900004821", "ahmed@example.invalid", now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("INSERT INTO app.profile_customer_data"), sql.getValue());
    assertEquals(profileId.toString(), args.getValue()[0]);
    assertEquals("+249900004821", args.getValue()[1]);
    assertEquals("ahmed@example.invalid", args.getValue()[2]);
  }

  @Test
  void insertContactDetailsAcceptsANullEmailAddress() {
    UUID profileId = UUID.randomUUID();

    repository.insertContactDetails(profileId, "+249900004821", null, Instant.now());

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(anyString(), args.capture(), any(int[].class));
    assertEquals(null, args.getValue()[2]);
  }

  @Test
  void upsertChannelWritesTheWireValuesNotTheEnumNames() {
    UUID profileId = UUID.randomUUID();

    repository.upsertChannel(profileId, MessageChannel.WHATSAPP, ChannelState.DECLINED);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("INSERT INTO app.profile_channel"), sql.getValue());
    assertTrue(
        sql.getValue().contains("ON CONFLICT (profile_id, channel) DO UPDATE"), sql.getValue());
    // customer.md: "The fresh OTP verification at 1b overwrites the recorded channel states
    // entirely" -- a channel that was VERIFIED before a re-entry must not stay verified once its
    // state resets to unverified/declined, or the row would be self-contradictory.
    assertTrue(sql.getValue().contains("verified_at = NULL"), sql.getValue());
    assertTrue(sql.getValue().contains("locked_at = NULL"), sql.getValue());
    assertTrue(sql.getValue().contains("wrong_code_attempts = 0"), sql.getValue());
    assertTrue(sql.getValue().contains("resend_count = 0"), sql.getValue());
    assertEquals(profileId.toString(), args.getValue()[0]);
    assertEquals("whatsapp", args.getValue()[1]);
    assertEquals("declined", args.getValue()[2]);
  }

  @Test
  void updateContactDetailsWritesPhoneAndEmailAndProfileId() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-29T12:00:00Z");

    repository.updateContactDetails(profileId, "+249900009999", "new@example.invalid", now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("UPDATE app.profile_customer_data"), sql.getValue());
    assertEquals("+249900009999", args.getValue()[0]);
    assertEquals("new@example.invalid", args.getValue()[1]);
    assertEquals(now.toString(), args.getValue()[2]);
    assertEquals(profileId.toString(), args.getValue()[3]);
  }

  @Test
  void declineChannelsNotInUpdatesOnlyTheChannelsNotKept() {
    UUID profileId = UUID.randomUUID();

    repository.declineChannelsNotIn(profileId, Set.of(MessageChannel.SMS, MessageChannel.WHATSAPP));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate, times(1)).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("SET state = 'declined'"), sql.getValue());
    assertEquals(profileId.toString(), args.getValue()[0]);
    assertEquals("email", args.getValue()[1]);
  }

  @Test
  void declineChannelsNotInDoesNothingWhenAllThreeChannelsAreKept() {
    UUID profileId = UUID.randomUUID();

    repository.declineChannelsNotIn(
        profileId, Set.of(MessageChannel.SMS, MessageChannel.WHATSAPP, MessageChannel.EMAIL));

    verify(jdbcTemplate, never()).update(anyString(), any(Object[].class), any(int[].class));
  }

  @Test
  void invalidateOtpChallengesSetsExpiresAtToLeastOfItselfAndNow() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-29T12:10:00Z");

    repository.invalidateOtpChallenges(profileId, now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("LEAST(expires_at"), sql.getValue());
    assertFalse(
        sql.getValue().toUpperCase(java.util.Locale.ROOT).contains("DELETE"), sql.getValue());
    assertEquals(now.toString(), args.getValue()[0]);
    assertEquals(profileId.toString(), args.getValue()[1]);
  }

  @SuppressWarnings("unchecked")
  @Test
  void findExistingReturnsEmptyWhenTheQueryHasNoRowsAndBindsOnlyTheAccountNumber() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());

    Optional<ExistingProfile> result = repository.findExisting("0000000001");

    assertTrue(result.isEmpty());
    // Mockito's default answer for an unstubbed List-returning method is already an empty list,
    // so the assertion above alone would pass even with the wrong SQL or argument order -- capture
    // and check what was actually sent, so this proves something beyond "returned empty".
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> accountNumber = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), accountNumber.capture());
    assertTrue(sql.getValue().contains("app.profile"), sql.getValue());
    assertTrue(sql.getValue().contains("is_terminal"), sql.getValue());
    // BL-032 / V0061: the branch is descriptive data and must not narrow the lookup.
    assertFalse(sql.getValue().contains("branch_code"), sql.getValue());
    assertEquals("0000000001", accountNumber.getValue());
  }

  @SuppressWarnings("unchecked")
  @Test
  void findExistingMapsTheRowIncludingIsTerminal() throws Exception {
    UUID profileId = UUID.randomUUID();
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("profile_id")).thenReturn(profileId.toString());
    when(rs.getString("status")).thenReturn("submitted");
    when(rs.getBoolean("is_terminal")).thenReturn(true);

    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            invocation -> {
              RowMapper<ExistingProfile> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 1));
            });

    Optional<ExistingProfile> result = repository.findExisting("0000000001");

    assertTrue(result.isPresent());
    assertEquals(profileId, result.get().profileId());
    assertEquals("submitted", result.get().status());
    assertTrue(result.get().terminal());
  }

  @Test
  void lockAndCheckStillEligibleForReentryIsTrueWhenNotTerminal() {
    UUID profileId = UUID.randomUUID();
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any()))
        .thenReturn(Boolean.FALSE); // is_terminal = false

    assertTrue(repository.lockAndCheckStillEligibleForReentry(profileId));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class), eq(profileId.toString()));
    assertTrue(sql.getValue().contains("FOR UPDATE"), sql.getValue());
  }

  @Test
  void lockAndCheckStillEligibleForReentryIsFalseWhenTerminal() {
    UUID profileId = UUID.randomUUID();
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any()))
        .thenReturn(Boolean.TRUE); // is_terminal = true

    assertFalse(repository.lockAndCheckStillEligibleForReentry(profileId));
  }

  @SuppressWarnings("unchecked")
  @Test
  void currentContactDetailsThrowsWhenNoRowExists() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());

    assertThrows(
        IllegalStateException.class, () -> repository.currentContactDetails(UUID.randomUUID()));
  }

  @SuppressWarnings("unchecked")
  @Test
  void currentContactDetailsMapsPhoneAndEmail() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("phone_number")).thenReturn("+249900001234");
    when(rs.getString("email_address")).thenReturn("a@example.invalid");

    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            invocation -> {
              RowMapper<ContactSnapshot> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 1));
            });

    ContactSnapshot result = repository.currentContactDetails(UUID.randomUUID());

    assertEquals("+249900001234", result.phoneNumber());
    assertEquals("a@example.invalid", result.emailAddress());
  }

  @SuppressWarnings("unchecked")
  @Test
  void currentChannelStatesReadsBackTheWireValues() throws Exception {
    UUID profileId = UUID.randomUUID();
    ResultSet row1 = mock(ResultSet.class);
    ResultSet row2 = mock(ResultSet.class);
    when(row1.getString("channel")).thenReturn("sms");
    when(row1.getString("state")).thenReturn("verified");
    when(row2.getString("channel")).thenReturn("whatsapp");
    when(row2.getString("state")).thenReturn("declined");

    doAnswer(
            invocation -> {
              RowCallbackHandler handler = invocation.getArgument(1);
              handler.processRow(row1);
              handler.processRow(row2);
              return null;
            })
        .when(jdbcTemplate)
        .query(anyString(), any(RowCallbackHandler.class), any());

    Map<MessageChannel, ChannelState> states = repository.currentChannelStates(profileId);

    assertEquals(ChannelState.VERIFIED, states.get(MessageChannel.SMS));
    assertEquals(ChannelState.DECLINED, states.get(MessageChannel.WHATSAPP));
    assertEquals(2, states.size());
  }

  @Test
  void touchLastActivityUpdatesOnlyLastActivityAt() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-29T12:20:00Z");

    repository.touchLastActivity(profileId, now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("SET last_activity_at"), sql.getValue());
    assertFalse(sql.getValue().contains("status ="), sql.getValue());
    assertEquals(now.toString(), args.getValue()[0]);
    assertEquals(profileId.toString(), args.getValue()[1]);
  }

 
  @Test
  void reactivateFromAbandonedUpdatesTheProfileThenInsertsTheHistoryRow() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-29T12:30:00Z");

    repository.reactivateFromAbandoned(profileId, now, 99L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate, times(2)).update(sql.capture(), args.capture(), any(int[].class));

    String profileSql = sql.getAllValues().get(0);
    assertTrue(profileSql.contains("SET status = 'in_progress'"), profileSql);
    assertTrue(profileSql.contains("row_version = row_version + 1"), profileSql);

    String historySql = sql.getAllValues().get(1);
    assertTrue(historySql.contains("INSERT INTO app.profile_status_history"), historySql);
    assertTrue(historySql.contains("'abandoned', 'in_progress', 'customer'"), historySql);
    Object[] historyArgs = args.getAllValues().get(1);
    assertEquals(profileId.toString(), historyArgs[0]);
    assertEquals(profileId.toString(), historyArgs[1]);
    assertEquals(99L, historyArgs[2]);
  }

  @Test
  void insertOtpChallengeWritesTheHashAndSaltNeverTheCode() {
    UUID challengeId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    byte[] hash = {1, 2, 3};
    byte[] salt = {4, 5, 6};
    Instant issuedAt = Instant.parse("2026-08-29T12:00:00Z");
    Instant expiresAt = issuedAt.plusSeconds(300);

    repository.insertOtpChallenge(
        challengeId, profileId, MessageChannel.SMS, hash, salt, issuedAt, expiresAt);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("INSERT INTO app.otp_challenge"), sql.getValue());
    assertTrue(
        sql.getValue().contains(", 0)"), "resend_index must be the literal 0: " + sql.getValue());
    Object[] bound = args.getValue();
    assertEquals(challengeId.toString(), bound[0]);
    assertEquals(profileId.toString(), bound[1]);
    assertEquals("sms", bound[2]);
    assertArrayEquals(hash, (byte[]) bound[3]);
    assertArrayEquals(salt, (byte[]) bound[4]);
    assertEquals(issuedAt.toString(), bound[5]);
    assertEquals(expiresAt.toString(), bound[6]);
  }

  // --- S3-08 / R-044: app.profile.phone_lock_until / phone_lock_escalated (V0037) -------------

  @Test
  void currentPhoneLockUntilReturnsEmptyWhenTheColumnIsNull() {
    UUID profileId = UUID.randomUUID();
    when(jdbcTemplate.queryForObject(
            anyString(), eq(java.sql.Timestamp.class), eq(profileId.toString())))
        .thenReturn(null);

    assertEquals(Optional.empty(), repository.currentPhoneLockUntil(profileId));
  }

  @Test
  void currentPhoneLockUntilMapsANonNullColumn() {
    UUID profileId = UUID.randomUUID();
    Instant until = Instant.parse("2026-08-30T12:15:00Z");
    when(jdbcTemplate.queryForObject(
            anyString(), eq(java.sql.Timestamp.class), eq(profileId.toString())))
        .thenReturn(java.sql.Timestamp.from(until));

    assertEquals(Optional.of(until), repository.currentPhoneLockUntil(profileId));
  }

  @Test
  void phoneLockEscalatedReadsTheColumn() {
    UUID profileId = UUID.randomUUID();
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(profileId.toString())))
        .thenReturn(Boolean.TRUE);

    assertTrue(repository.phoneLockEscalated(profileId));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class), eq(profileId.toString()));
    assertTrue(sql.getValue().contains("phone_lock_escalated"), sql.getValue());
  }

  @Test
  void applyPhoneSessionLockSetsUntilAndEscalatedTogether() {
    UUID profileId = UUID.randomUUID();
    Instant until = Instant.parse("2026-08-30T12:15:00Z");

    repository.applyPhoneSessionLock(profileId, until);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));

    assertTrue(sql.getValue().contains("phone_lock_until"), sql.getValue());
    assertTrue(sql.getValue().contains("phone_lock_escalated = true"), sql.getValue());
    assertEquals(until.toString(), args.getValue()[0]);
    assertEquals(profileId.toString(), args.getValue()[1]);
  }

  @Test
  void lockProfileRowUsesForUpdateOnAppProfile() {
    UUID profileId = UUID.randomUUID();

    repository.lockProfileRow(profileId);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    verify(jdbcTemplate).query(sql.capture(), setter.capture(), any(ResultSetExtractor.class));
    assertTrue(sql.getValue().contains("FROM app.profile"), sql.getValue());
    assertTrue(sql.getValue().contains("FOR UPDATE"), sql.getValue());
  }
}
