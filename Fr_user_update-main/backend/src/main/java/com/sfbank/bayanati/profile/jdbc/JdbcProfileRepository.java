package com.sfbank.bayanati.profile.jdbc;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.sql.Types;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code app.profile}, {@code app.profile_status_history}, {@code
 * app.profile_customer_data}, {@code app.profile_channel} and {@code app.otp_challenge} as {@code
 * fru_app}. The single place in the application that knows these tables' shapes — mirrors {@code
 * JdbcAuditEventWriter}'s and {@code JdbcNotificationOutboxRepository}'s single-writer discipline.
 */
@Repository
public class JdbcProfileRepository implements ProfileRepository {

  private static final String ENSURE_AUDIT_CHAIN = "SELECT audit.ensure_profile_chain(?::uuid)";

  private static final String LOCK_PROFILE_ROW =
      "SELECT 1 FROM app.profile WHERE profile_id = ?::uuid FOR UPDATE";

  /**
   * {@code reference_number}, {@code submitted_at} and {@code pii_purged_at} are left NULL (their
   * natural state before submission); {@code provenance}, {@code resume_stage} and {@code
   * row_version} are left to their column defaults (V0005) — this insert only supplies what Stage
   * 1b actually knows.
   */
  private static final String INSERT_PROFILE =
    """
    INSERT INTO app.profile
      (profile_id, account_number, status, status_changed_at,
       created_at, last_activity_at)
    VALUES (?::uuid, ?::text, 'in_progress', ?::timestamptz,
            ?::timestamptz, ?::timestamptz)
    """;

  /**
   * {@code seq=1}: the first history row for a brand-new profile. {@code from_status} is explicitly
   * NULL — profile creation, no prior status (V0020's own transition-table comment). {@code
   * actor_kind='customer'}: the customer's own Stage 1b submission, so {@code actor_id} stays NULL
   * (V0009's {@code operator_named} check only requires it for {@code 'operator'}).
   */
  private static final String INSERT_FIRST_STATUS_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)
      VALUES (?::uuid, 1, NULL, 'in_progress', 'customer', ?::bigint)
      """;

  private static final String INSERT_CONTACT_DETAILS =
      """
      INSERT INTO app.profile_customer_data (profile_id, phone_number, email_address, updated_at)
      VALUES (?::uuid, ?::text, ?::text, ?::timestamptz)
      """;

  private static final String UPDATE_CONTACT_DETAILS =
      """
      UPDATE app.profile_customer_data
         SET phone_number = ?::text, email_address = ?::text, updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  private static final String UPDATE_EMAIL_ADDRESS =
      """
      UPDATE app.profile_customer_data
         SET email_address = ?::text, updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  /**
   * {@code ON CONFLICT (profile_id, channel)} — {@code app.profile_channel}'s own PRIMARY KEY
   * (V0007) — so this one statement serves both a brand-new profile's first channels (never
   * conflicts) and a re-entry's replaced ones. The reset columns match a fresh, never-attempted
   * challenge (V0007's own defaults for a plain INSERT).
   */
  private static final String UPSERT_CHANNEL =
      """
      INSERT INTO app.profile_channel (profile_id, channel, state)
      VALUES (?::uuid, ?::text, ?::text)
      ON CONFLICT (profile_id, channel) DO UPDATE
        SET state = EXCLUDED.state, verified_at = NULL, locked_at = NULL,
            wrong_code_attempts = 0, resend_count = 0
      """;

  private static final String DECLINE_CHANNEL =
      """
      UPDATE app.profile_channel
         SET state = 'declined', verified_at = NULL, locked_at = NULL,
             wrong_code_attempts = 0, resend_count = 0
       WHERE profile_id = ?::uuid AND channel = ?::text
      """;

  private static final String INSERT_OTP_CHALLENGE =
      """
      INSERT INTO app.otp_challenge
        (challenge_id, profile_id, channel, code_hash, salt, issued_at, expires_at, resend_index)
      VALUES (?::uuid, ?::uuid, ?::text, ?::bytea, ?::bytea, ?::timestamptz, ?::timestamptz, 0)
      """;

  /**
   * Expires, never deletes — {@code app.otp_challenge} grants {@code fru_app} no {@code DELETE}
   * (V0010: "history of challenges is harmless to retain and useful for lockout/resend
   * accounting"), and a Stage 1b re-entry must not destroy the evidence that a code was issued.
   * {@code LEAST} never moves an already-earlier {@code expires_at} later.
   */
  private static final String INVALIDATE_OTP_CHALLENGES =
      """
      UPDATE app.otp_challenge SET expires_at = LEAST(expires_at, ?::timestamptz)
       WHERE profile_id = ?::uuid
      """;

  /**
   * Account number alone — {@code profile_one_per_account} is {@code UNIQUE (account_number)} since
   * V0061 (BL-032), so this returns at most one row without any branch predicate.
   */
  private static final String FIND_EXISTING =
      """
      SELECT p.profile_id, p.status, sc.is_terminal
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
       WHERE p.account_number = ?::text
      """;

  /**
   * {@code FOR UPDATE}: takes a row lock on {@code app.profile} so nothing else can change its
   * status until this (already-open, I/O-free) transaction commits or rolls back — closing the
   * window between {@code findExisting}'s pre-send-loop read and this transaction's writes.
   */
  private static final String LOCK_AND_CHECK_STILL_ELIGIBLE =
      """
      SELECT sc.is_terminal
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
       WHERE p.profile_id = ?::uuid
         FOR UPDATE OF p
      """;

  private static final String CURRENT_CHANNEL_STATES =
      "SELECT channel, state FROM app.profile_channel WHERE profile_id = ?::uuid";

  private static final String CURRENT_CONTACT_DETAILS =
      "SELECT phone_number, email_address FROM app.profile_customer_data WHERE profile_id = ?::uuid";

  private static final String TOUCH_LAST_ACTIVITY =
      "UPDATE app.profile SET last_activity_at = ?::timestamptz WHERE profile_id = ?::uuid";

  /** No {@code row_version} bump, deliberately — see {@code ProfileRepository#updateBranchCode}. */
  

  private static final String REACTIVATE_FROM_ABANDONED =
      """
      UPDATE app.profile
         SET status = 'in_progress', status_changed_at = ?::timestamptz,
             last_activity_at = ?::timestamptz, row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  /**
   * {@code seq} is computed from the profile's own history rather than passed in — this is the only
   * writer that appends a history row to a profile that may already have one (S3-06's {@code
   * insertProfile} is always {@code seq=1}, the profile's first row).
   */
  private static final String INSERT_REACTIVATION_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
              'abandoned', 'in_progress', 'customer', ?::bigint)
      """;

  private static final String CURRENT_PHONE_LOCK_UNTIL =
      "SELECT phone_lock_until FROM app.profile WHERE profile_id = ?::uuid";

  private static final String PHONE_LOCK_ESCALATED =
      "SELECT phone_lock_escalated FROM app.profile WHERE profile_id = ?::uuid";

  private static final String APPLY_PHONE_SESSION_LOCK =
      """
      UPDATE app.profile SET phone_lock_until = ?::timestamptz, phone_lock_escalated = true
       WHERE profile_id = ?::uuid
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcProfileRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void ensureAuditChain(UUID profileId) {
    jdbcTemplate.query(ENSURE_AUDIT_CHAIN, ps -> ps.setString(1, profileId.toString()), rs -> null);
  }

  @Override
  public void lockProfileRow(UUID profileId) {
    jdbcTemplate.query(LOCK_PROFILE_ROW, ps -> ps.setString(1, profileId.toString()), rs -> null);
  }

  @Ovwerride
  public void insertProfile(
    UUID profileId, String accountNumber, Instant now, long auditEventId) {

  String nowText = now.toString();

  jdbcTemplate.update(
      INSERT_PROFILE,
      new Object[] {
        profileId.toString(),
        accountNumber,
        nowText,
        nowText,
        nowText
      },
      new int[] {
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR
      });

  jdbcTemplate.update(
      INSERT_FIRST_STATUS_HISTORY,
      new Object[] {profileId.toString(), auditEventId},
      new int[] {Types.VARCHAR, Types.BIGINT});
}
  @Override
  public void insertContactDetails(
      UUID profileId, String phoneNumber, String emailAddress, Instant now) {
    jdbcTemplate.update(
        INSERT_CONTACT_DETAILS,
        new Object[] {profileId.toString(), phoneNumber, emailAddress, now.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public void updateContactDetails(
      UUID profileId, String phoneNumber, String emailAddress, Instant now) {
    jdbcTemplate.update(
        UPDATE_CONTACT_DETAILS,
        new Object[] {phoneNumber, emailAddress, now.toString(), profileId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public void updateEmailAddress(UUID profileId, String emailAddress, Instant now) {
    jdbcTemplate.update(
        UPDATE_EMAIL_ADDRESS,
        new Object[] {emailAddress, now.toString(), profileId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public void upsertChannel(UUID profileId, MessageChannel channel, ChannelState state) {
    jdbcTemplate.update(
        UPSERT_CHANNEL,
        new Object[] {profileId.toString(), channel.wireValue(), state.wireValue()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public void declineChannelsNotIn(UUID profileId, Set<MessageChannel> keep) {
    for (MessageChannel channel : MessageChannel.values()) {
      if (keep.contains(channel)) {
        continue;
      }
      jdbcTemplate.update(
          DECLINE_CHANNEL,
          new Object[] {profileId.toString(), channel.wireValue()},
          new int[] {Types.VARCHAR, Types.VARCHAR});
    }
  }

  @Override
  public void insertOtpChallenge(
      UUID challengeId,
      UUID profileId,
      MessageChannel channel,
      byte[] codeHash,
      byte[] salt,
      Instant issuedAt,
      Instant expiresAt) {
    jdbcTemplate.update(
        INSERT_OTP_CHALLENGE,
        new Object[] {
          challengeId.toString(),
          profileId.toString(),
          channel.wireValue(),
          codeHash,
          salt,
          issuedAt.toString(),
          expiresAt.toString()
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARBINARY,
          Types.VARBINARY,
          Types.VARCHAR,
          Types.VARCHAR
        });
  }

  @Override
  public void invalidateOtpChallenges(UUID profileId, Instant now) {
    jdbcTemplate.update(
        INVALIDATE_OTP_CHALLENGES,
        new Object[] {now.toString(), profileId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR});
  }

  @Override
  public Optional<ExistingProfile> findExisting(String accountNumber) {
    List<ExistingProfile> rows =
        jdbcTemplate.query(
            FIND_EXISTING,
            (rs, rowNum) ->
                new ExistingProfile(
                    UUID.fromString(rs.getString("profile_id")),
                    rs.getString("status"),
                    rs.getBoolean("is_terminal")),
            accountNumber);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public boolean lockAndCheckStillEligibleForReentry(UUID profileId) {
    Boolean isTerminal =
        jdbcTemplate.queryForObject(
            LOCK_AND_CHECK_STILL_ELIGIBLE, Boolean.class, profileId.toString());
    return isTerminal != null && !isTerminal;
  }

  @Override
  public Map<MessageChannel, ChannelState> currentChannelStates(UUID profileId) {
    Map<MessageChannel, ChannelState> states = new EnumMap<>(MessageChannel.class);
    jdbcTemplate.query(
        CURRENT_CHANNEL_STATES,
        rs -> {
          states.put(
              MessageChannel.fromWireValue(rs.getString("channel")),
              ChannelState.fromWireValue(rs.getString("state")));
        },
        profileId.toString());
    return states;
  }

  @Override
  public ContactSnapshot currentContactDetails(UUID profileId) {
    List<ContactSnapshot> rows =
        jdbcTemplate.query(
            CURRENT_CONTACT_DETAILS,
            (rs, rowNum) ->
                new ContactSnapshot(rs.getString("phone_number"), rs.getString("email_address")),
            profileId.toString());
    if (rows.isEmpty()) {
      throw new IllegalStateException("no app.profile_customer_data row for profile " + profileId);
    }
    return rows.get(0);
  }

  @Override
  public void touchLastActivity(UUID profileId, Instant now) {
    jdbcTemplate.update(
        TOUCH_LAST_ACTIVITY,
        new Object[] {now.toString(), profileId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR});
  }

  

  @Override
  public void reactivateFromAbandoned(UUID profileId, Instant now, long auditEventId) {
    String nowText = now.toString();
    jdbcTemplate.update(
        REACTIVATE_FROM_ABANDONED,
        new Object[] {nowText, nowText, profileId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
    jdbcTemplate.update(
        INSERT_REACTIVATION_HISTORY,
        new Object[] {profileId.toString(), profileId.toString(), auditEventId},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.BIGINT});
  }

  @Override
  public Optional<Instant> currentPhoneLockUntil(UUID profileId) {
    java.sql.Timestamp result =
        jdbcTemplate.queryForObject(
            CURRENT_PHONE_LOCK_UNTIL, java.sql.Timestamp.class, profileId.toString());
    return Optional.ofNullable(result).map(java.sql.Timestamp::toInstant);
  }

  @Override
  public boolean phoneLockEscalated(UUID profileId) {
    Boolean result =
        jdbcTemplate.queryForObject(PHONE_LOCK_ESCALATED, Boolean.class, profileId.toString());
    return Boolean.TRUE.equals(result);
  }

  @Override
  public void applyPhoneSessionLock(UUID profileId, Instant until) {
    jdbcTemplate.update(
        APPLY_PHONE_SESSION_LOCK,
        new Object[] {until.toString(), profileId.toString()},
        new int[] {Types.VARCHAR, Types.VARCHAR});
  }
}
