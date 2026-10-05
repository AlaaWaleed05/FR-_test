package com.sfbank.bayanati.otpverification.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.otpverification.domain.ChannelVerificationState;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The statement shapes and parameter bindings, against a mocked JdbcTemplate — same reasoning as
 * {@code JdbcProfileRepositoryTest}: {@code OtpVerificationIntegrationTest} proves this class
 * against a real PostgreSQL but is excluded from the default {@code ./mvnw verify} run.
 */
class JdbcOtpVerificationRepositoryTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final JdbcOtpVerificationRepository repository =
      new JdbcOtpVerificationRepository(jdbcTemplate);

  @SuppressWarnings("unchecked")
  @Test
  void findChannelStateReturnsEmptyWhenNoRow() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    assertEquals(
        Optional.empty(), repository.findChannelState(UUID.randomUUID(), MessageChannel.SMS));
  }

  @SuppressWarnings("unchecked")
  @Test
  void findChannelStateMapsAllFourColumns() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("state")).thenReturn("unverified");
    Timestamp lockedAt = Timestamp.from(Instant.parse("2026-08-30T12:00:00Z"));
    when(rs.getTimestamp("locked_at")).thenReturn(lockedAt);
    when(rs.getInt("wrong_code_attempts")).thenReturn(3);
    when(rs.getInt("resend_count")).thenReturn(1);

    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            invocation -> {
              RowMapper<ChannelVerificationState> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 1));
            });

    ChannelVerificationState state =
        repository.findChannelState(UUID.randomUUID(), MessageChannel.SMS).orElseThrow();

    assertEquals(ChannelState.UNVERIFIED, state.state());
    assertEquals(lockedAt.toInstant(), state.lockedAt());
    assertEquals(3, state.wrongCodeAttempts());
    assertEquals(1, state.resendCount());
    assertTrue(state.locked());
  }

  @SuppressWarnings("unchecked")
  @Test
  void findChannelStateForUpdateUsesForUpdateAndMapsTheRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("state")).thenReturn("unverified");
    when(rs.getTimestamp("locked_at")).thenReturn(null);
    when(rs.getInt("wrong_code_attempts")).thenReturn(2);
    when(rs.getInt("resend_count")).thenReturn(0);

    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            invocation -> {
              RowMapper<ChannelVerificationState> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 1));
            });

    ChannelVerificationState state =
        repository.findChannelStateForUpdate(UUID.randomUUID(), MessageChannel.SMS);

    assertEquals(2, state.wrongCodeAttempts());
    assertFalse(state.locked());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(), any());
    assertTrue(sql.getValue().contains("FOR UPDATE"), sql.getValue());
  }

  @SuppressWarnings("unchecked")
  @Test
  void findChannelStateForUpdateThrowsWhenNoRow() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    assertThrows(
        IllegalStateException.class,
        () -> repository.findChannelStateForUpdate(UUID.randomUUID(), MessageChannel.SMS));
  }

  @SuppressWarnings("unchecked")
  @Test
  void findCurrentChallengeOrdersByIssuedAtDescendingWithLimitOne() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    repository.findCurrentChallenge(UUID.randomUUID(), MessageChannel.SMS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(), any());
    assertTrue(sql.getValue().contains("ORDER BY issued_at DESC"), sql.getValue());
    assertTrue(sql.getValue().contains("LIMIT 1"), sql.getValue());
  }

  @Test
  void consumeChallengeSetsConsumedAt() {
    UUID challengeId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-30T12:05:00Z");

    repository.consumeChallenge(challengeId, now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));
    assertTrue(sql.getValue().contains("consumed_at"), sql.getValue());
    assertEquals(now.toString(), args.getValue()[0]);
    assertEquals(challengeId.toString(), args.getValue()[1]);
  }

  @Test
  void markVerifiedSetsStateAndVerifiedAt() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-30T12:05:00Z");

    repository.markVerified(profileId, MessageChannel.WHATSAPP, now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sql.capture(), any(Object[].class), any(int[].class));
    assertTrue(sql.getValue().contains("state = 'verified'"), sql.getValue());
    assertTrue(sql.getValue().contains("verified_at"), sql.getValue());
  }

  @Test
  void incrementWrongAttemptsUsesReturningAndDefaultsToZeroOnNull() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
        .thenReturn(null);

    assertEquals(0, repository.incrementWrongAttempts(UUID.randomUUID(), MessageChannel.SMS));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), any(), any());
    assertTrue(
        sql.getValue().contains("wrong_code_attempts = wrong_code_attempts + 1"), sql.getValue());
    assertTrue(sql.getValue().contains("RETURNING wrong_code_attempts"), sql.getValue());
  }

  @Test
  void lockChannelSetsLockedAt() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-30T12:05:00Z");

    repository.lockChannel(profileId, MessageChannel.SMS, now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sql.capture(), any(Object[].class), any(int[].class));
    assertTrue(sql.getValue().contains("locked_at ="), sql.getValue());
  }

  @Test
  void allSelectedPhoneChannelsLockedBindsProfileIdTwiceAndReadsTheBoolean() {
    UUID profileId = UUID.randomUUID();
    when(jdbcTemplate.queryForObject(
            anyString(), eq(Boolean.class), eq(profileId.toString()), eq(profileId.toString())))
        .thenReturn(Boolean.TRUE);

    assertTrue(repository.allSelectedPhoneChannelsLocked(profileId));
  }

  @Test
  void allSelectedPhoneChannelsLockedIsFalseWhenTheQueryReturnsNull() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(), any()))
        .thenReturn(null);

    assertFalse(repository.allSelectedPhoneChannelsLocked(UUID.randomUUID()));
  }

  @Test
  void invalidateChallengesForChannelScopesToOneChannel() {
    UUID profileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-30T12:05:00Z");

    repository.invalidateChallengesForChannel(profileId, MessageChannel.SMS, now);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));
    assertTrue(sql.getValue().contains("LEAST(expires_at"), sql.getValue());
    assertTrue(sql.getValue().contains("AND channel ="), sql.getValue());
    assertEquals("sms", args.getValue()[2]);
  }

  @Test
  void insertChallengeBindsResendIndexAsGiven() {
    UUID challengeId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    byte[] hash = {1, 2, 3};
    byte[] salt = {4, 5, 6};
    Instant issuedAt = Instant.parse("2026-08-30T12:00:00Z");
    Instant expiresAt = issuedAt.plusSeconds(300);

    repository.insertChallenge(
        challengeId, profileId, MessageChannel.WHATSAPP, hash, salt, issuedAt, expiresAt, 2);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sql.capture(), args.capture(), any(int[].class));
    assertTrue(sql.getValue().contains("INSERT INTO app.otp_challenge"), sql.getValue());
    Object[] bound = args.getValue();
    assertEquals(challengeId.toString(), bound[0]);
    assertEquals(profileId.toString(), bound[1]);
    assertEquals("whatsapp", bound[2]);
    assertArrayEquals(hash, (byte[]) bound[3]);
    assertArrayEquals(salt, (byte[]) bound[4]);
    assertEquals(issuedAt.toString(), bound[5]);
    assertEquals(expiresAt.toString(), bound[6]);
    assertEquals(2, bound[7]);
  }

  @Test
  void incrementResendCountUsesReturning() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);

    assertEquals(2, repository.incrementResendCount(UUID.randomUUID(), MessageChannel.EMAIL));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), any(), any());
    assertTrue(sql.getValue().contains("resend_count = resend_count + 1"), sql.getValue());
    assertTrue(sql.getValue().contains("RETURNING resend_count"), sql.getValue());
  }

  @Test
  void mostRecentIssuedAtReturnsNullWhenNoRows() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any(), any()))
        .thenReturn(null);

    assertEquals(null, repository.mostRecentIssuedAt(UUID.randomUUID(), MessageChannel.SMS));
  }

  @Test
  void mostRecentIssuedAtMapsTheTimestamp() {
    Instant expected = Instant.parse("2026-08-30T12:00:00Z");
    when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any(), any()))
        .thenReturn(Timestamp.from(expected));

    assertEquals(expected, repository.mostRecentIssuedAt(UUID.randomUUID(), MessageChannel.SMS));
  }
}
