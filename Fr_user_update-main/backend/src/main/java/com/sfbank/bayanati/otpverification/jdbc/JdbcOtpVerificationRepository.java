package com.sfbank.bayanati.otpverification.jdbc;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.otpverification.domain.ChannelVerificationState;
import com.sfbank.bayanati.otpverification.domain.CurrentChallenge;
import com.sfbank.bayanati.otpverification.domain.OtpVerificationRepository;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes and reads {@code app.profile_channel}'s verification columns and {@code app.otp_challenge}
 * for Stage 2. The single place in the application that knows these tables' verification-related
 * shapes, mirroring {@code JdbcProfileRepository}'s single-writer discipline.
 */
@Repository
public class JdbcOtpVerificationRepository implements OtpVerificationRepository {

  private static final String FIND_CHANNEL_STATE =
      """
      SELECT state, locked_at, wrong_code_attempts, resend_count
        FROM app.profile_channel
       WHERE profile_id = ?::uuid AND channel = ?::text
      """;

  private static final String FIND_CHANNEL_STATE_FOR_UPDATE =
      """
      SELECT state, locked_at, wrong_code_attempts, resend_count
        FROM app.profile_channel
       WHERE profile_id = ?::uuid AND channel = ?::text
         FOR UPDATE
      """;

  private static final String FIND_CURRENT_CHALLENGE =
      """
      SELECT challenge_id, code_hash, salt, issued_at, expires_at
        FROM app.otp_challenge
       WHERE profile_id = ?::uuid AND channel = ?::text
       ORDER BY issued_at DESC
       LIMIT 1
      """;

  private static final String CONSUME_CHALLENGE =
      "UPDATE app.otp_challenge SET consumed_at = ?::timestamptz WHERE challenge_id = ?::uuid";

  private static final String MARK_VERIFIED =
      """
      UPDATE app.profile_channel SET state = 'verified', verified_at = ?::timestamptz
       WHERE profile_id = ?::uuid AND channel = ?::text
      """;

  private static final String INCREMENT_WRONG_ATTEMPTS =
      """
      UPDATE app.profile_channel SET wrong_code_attempts = wrong_code_attempts + 1
       WHERE profile_id = ?::uuid AND channel = ?::text
      RETURNING wrong_code_attempts
      """;

  private static final String LOCK_CHANNEL =
      "UPDATE app.profile_channel SET locked_at = ?::timestamptz"
          + " WHERE profile_id = ?::uuid AND channel = ?::text";

  /**
   * True exactly when every non-{@code declined} phone-channel row is either locked or verified,
   * AND none is verified, AND at least one such row exists — i.e. no phone channel remains that
   * could still be verified this session (customer.md Stage 2: "Both phone channels locked, or the
   * only selected phone channel locked").
   */
  private static final String ALL_SELECTED_PHONE_CHANNELS_LOCKED =
      """
      SELECT EXISTS (
        SELECT 1 FROM app.profile_channel
         WHERE profile_id = ?::uuid AND channel IN ('sms','whatsapp') AND state <> 'declined'
      ) AND NOT EXISTS (
        SELECT 1 FROM app.profile_channel
         WHERE profile_id = ?::uuid AND channel IN ('sms','whatsapp') AND state <> 'declined'
           AND (state = 'verified' OR locked_at IS NULL)
      )
      """;

  private static final String INVALIDATE_CHALLENGES_FOR_CHANNEL =
      """
      UPDATE app.otp_challenge SET expires_at = LEAST(expires_at, ?::timestamptz)
       WHERE profile_id = ?::uuid AND channel = ?::text
      """;

  private static final String INSERT_CHALLENGE =
      """
      INSERT INTO app.otp_challenge
        (challenge_id, profile_id, channel, code_hash, salt, issued_at, expires_at, resend_index)
      VALUES (?::uuid, ?::uuid, ?::text, ?::bytea, ?::bytea, ?::timestamptz, ?::timestamptz, ?::smallint)
      """;

  private static final String INCREMENT_RESEND_COUNT =
      """
      UPDATE app.profile_channel SET resend_count = resend_count + 1
       WHERE profile_id = ?::uuid AND channel = ?::text
      RETURNING resend_count
      """;

  private static final String MOST_RECENT_ISSUED_AT =
      "SELECT MAX(issued_at) FROM app.otp_challenge WHERE profile_id = ?::uuid AND channel = ?::text";

  private final JdbcTemplate jdbcTemplate;

  public JdbcOtpVerificationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ChannelVerificationState> findChannelState(
      UUID profileId, MessageChannel channel) {
    List<ChannelVerificationState> rows =
        jdbcTemplate.query(
            FIND_CHANNEL_STATE,
            (rs, rowNum) ->
                new ChannelVerificationState(
                    ChannelState.fromWireValue(rs.getString("state")),
                    instantOrNull(rs.getTimestamp("locked_at")),
                    rs.getInt("wrong_code_attempts"),
                    rs.getInt("resend_count")),
            profileId.toString(),
            channel.wireValue());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public ChannelVerificationState findChannelStateForUpdate(
      UUID profileId, MessageChannel channel) {
    List<ChannelVerificationState> rows =
        jdbcTemplate.query(
            FIND_CHANNEL_STATE_FOR_UPDATE,
            (rs, rowNum) ->
                new ChannelVerificationState(
                    ChannelState.fromWireValue(rs.getString("state")),
                    instantOrNull(rs.getTimestamp("locked_at")),
                    rs.getInt("wrong_code_attempts"),
                    rs.getInt("resend_count")),
            profileId.toString(),
            channel.wireValue());
    if (rows.isEmpty()) {
      throw new IllegalStateException(
          "no app.profile_channel row for profile "
              + profileId
              + " channel "
              + channel.wireValue()
              + " -- caller must confirm existence via findChannelState first");
    }
    return rows.get(0);
  }

  @Override
  public Optional<CurrentChallenge> findCurrentChallenge(UUID profileId, MessageChannel channel) {
    List<CurrentChallenge> rows =
        jdbcTemplate.query(
            FIND_CURRENT_CHALLENGE,
            (rs, rowNum) ->
                new CurrentChallenge(
                    UUID.fromString(rs.getString("challenge_id")),
                    rs.getBytes("code_hash"),
                    rs.getBytes("salt"),
                    rs.getTimestamp("issued_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()),
            profileId.toString(),
            channel.wireValue());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void consumeChallenge(UUID challengeId, Instant now) {
    jdbcTemplate.update(
        CONSUME_CHALLENGE,
        new Object[] {now.toString(), challengeId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public void markVerified(UUID profileId, MessageChannel channel, Instant now) {
    jdbcTemplate.update(
        MARK_VERIFIED,
        new Object[] {now.toString(), profileId.toString(), channel.wireValue()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public int incrementWrongAttempts(UUID profileId, MessageChannel channel) {
    Integer result =
        jdbcTemplate.queryForObject(
            INCREMENT_WRONG_ATTEMPTS, Integer.class, profileId.toString(), channel.wireValue());
    return result == null ? 0 : result;
  }

  @Override
  public void lockChannel(UUID profileId, MessageChannel channel, Instant now) {
    jdbcTemplate.update(
        LOCK_CHANNEL,
        new Object[] {now.toString(), profileId.toString(), channel.wireValue()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public boolean allSelectedPhoneChannelsLocked(UUID profileId) {
    Boolean result =
        jdbcTemplate.queryForObject(
            ALL_SELECTED_PHONE_CHANNELS_LOCKED,
            Boolean.class,
            profileId.toString(),
            profileId.toString());
    return Boolean.TRUE.equals(result);
  }

  @Override
  public void invalidateChallengesForChannel(UUID profileId, MessageChannel channel, Instant now) {
    jdbcTemplate.update(
        INVALIDATE_CHALLENGES_FOR_CHANNEL,
        new Object[] {now.toString(), profileId.toString(), channel.wireValue()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public void insertChallenge(
      UUID challengeId,
      UUID profileId,
      MessageChannel channel,
      byte[] codeHash,
      byte[] salt,
      Instant issuedAt,
      Instant expiresAt,
      int resendIndex) {
    jdbcTemplate.update(
        INSERT_CHALLENGE,
        new Object[] {
          challengeId.toString(),
          profileId.toString(),
          channel.wireValue(),
          codeHash,
          salt,
          issuedAt.toString(),
          expiresAt.toString(),
          resendIndex
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARBINARY,
          Types.VARBINARY,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.SMALLINT
        });
  }

  @Override
  public int incrementResendCount(UUID profileId, MessageChannel channel) {
    Integer result =
        jdbcTemplate.queryForObject(
            INCREMENT_RESEND_COUNT, Integer.class, profileId.toString(), channel.wireValue());
    return result == null ? 0 : result;
  }

  @Override
  public Instant mostRecentIssuedAt(UUID profileId, MessageChannel channel) {
    Timestamp result =
        jdbcTemplate.queryForObject(
            MOST_RECENT_ISSUED_AT, Timestamp.class, profileId.toString(), channel.wireValue());
    return result == null ? null : result.toInstant();
  }

  private static Instant instantOrNull(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
