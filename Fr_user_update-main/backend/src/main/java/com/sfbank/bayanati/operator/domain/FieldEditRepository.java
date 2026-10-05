package com.sfbank.bayanati.operator.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code reads and writes a single operator-keyed field.
 *
 * <p>Separate from {@link ProfileViewRepository} on purpose: that port's only implementation loads
 * a whole {@code ProfileDetail}, and {@code OperatorProfileViewService} writes a {@code
 * profile_viewed} audit event around it. Reusing either on the edit path would record a view that
 * never happened on every edit. {@link #editabilityFacts} is the narrow read instead.
 */
public interface FieldEditRepository {

  /**
   * {@code SELECT status FROM app.profile WHERE profile_id = ? FOR UPDATE} — must be the
   * transaction's FIRST statement.
   *
   * <p><strong>Lock ordering, and it is not optional.</strong> {@code ReviewRepository}'s Javadoc
   * records the invariant every writer in this codebase obeys: the {@code app.profile} row lock is
   * taken BEFORE the audit-chain lock that {@code AuditEventWriter#append} acquires inside {@code
   * audit.chain_append()}. Two concurrent writers taking them in opposite orders deadlocked live at
   * S4-01 (PostgreSQL {@code 40P01}). {@code FieldEditService} therefore calls this first, appends
   * its audit event second, and updates the column third.
   *
   * @return the profile's current status, or empty if no such profile exists
   */
  Optional<String> lockAndReadStatus(UUID profileId);

  /**
   * The six values {@link EditableFieldPolicy} needs, in one query — never a full profile load.
   *
   * @return empty if no such profile exists
   */
  Optional<EditabilityFacts> editabilityFacts(UUID profileId);

  /**
   * This field's value as currently stored, for the audit event's {@code previousValue} and for the
   * {@code app.profile_field_edit} row. Empty when the column is NULL.
   */
  Optional<String> currentValue(UUID profileId, EditableField field);

  /**
   * Writes the new value into the field's own column.
   *
   * @return {@code false} if no row was updated — for {@link
   *     EditableField.Target#INCOME_SOURCE_OTHER} that means the profile has no {@code OTHER}
   *     income row, which {@link EditableFieldPolicy} should already have excluded; the caller
   *     turns it into a 409 rather than reporting a success that wrote nothing
   */
  boolean updateValue(UUID profileId, EditableField field, String value, Instant now);

  /**
   * Upserts the {@code app.profile_field_edit} row — one per edited field, last edit winning. The
   * tamper-evident history is the audit chain; this is the per-field provenance the derived {@code
   * app.profile.provenance} predicate and S9-03's «معدَّل» marker both read.
   */
  void recordEdit(
      UUID profileId,
      EditableField field,
      String previousValue,
      String newValue,
      String editedBy,
      Instant editedAt);
}
