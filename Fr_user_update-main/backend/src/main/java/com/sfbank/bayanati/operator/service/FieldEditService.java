package com.sfbank.bayanati.operator.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.EditabilityFacts;
import com.sfbank.bayanati.operator.domain.EditableField;
import com.sfbank.bayanati.operator.domain.EditableFieldPolicy;
import com.sfbank.bayanati.operator.domain.FieldEditRejectedException;
import com.sfbank.bayanati.operator.domain.FieldEditRepository;
import com.sfbank.bayanati.operator.domain.FieldEditValidator;
import com.sfbank.bayanati.operator.domain.FieldNotEditableException;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorAuditPayload;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BL-135, AD-015's actual build: an operator keys one customer-entered field.
 *
 * <p>Mirrors {@code OperatorReviewService}'s shape — a level check, then one transaction covering
 * the lock, the audit event, the column write and the per-field provenance row together.
 *
 * <p><strong>Three bounds, each one load-bearing rather than defensive.</strong>
 *
 * <ul>
 *   <li><strong>Viewers cannot edit.</strong> AD-015 names it as one of the bounds that make a
 *       data-entry back office safe.
 *   <li><strong>Only {@code submitted} and {@code rejected} may be edited</strong> — product-owner
 *       ruling, 2026-09-16, narrowing AD-015's literal "any status before {@code approved}". The
 *       reason is not caution: {@code app.profile_customer_data} is DEVICE-AUTHORITATIVE on
 *       reconcile (V0006's own header), and every customer stage write is a full-row replace —
 *       {@code JdbcDataEntryRepository.UPDATE_STAGE5} sets all eleven home-address columns
 *       unconditionally. An operator edit to a profile still {@code in_progress} would be silently
 *       destroyed by the customer's next stage submission, with no conflict, no error and nothing
 *       in the audit trail saying the edit was lost. {@code rejected} is included because a
 *       rejected profile can still be approved ({@code JdbcReviewRepository.APPROVE}'s own {@code
 *       WHERE status IN ('submitted','rejected')}), so it is genuinely "before approved"; editing
 *       stops at {@code approved} and never resumes (AD-015).
 *   <li><strong>Editability is re-derived here, never taken from the request.</strong> The browser
 *       is told which fields carry a «تعديل» chip, but a chip is presentation. {@link
 *       EditableFieldPolicy} runs again on this path against the profile's own stored values.
 * </ul>
 *
 * <p><strong>Lock ordering.</strong> {@code app.profile}'s row lock is taken first, the audit-chain
 * lock second — the invariant {@code ReviewRepository}'s Javadoc records, after two writers taking
 * them in opposite orders deadlocked live at S4-01.
 *
 * <p><strong>No notification is sent.</strong> BL-135's settled behaviour notifies the customer
 * only when a CONTACT CHANNEL changes, and AD-022 ruling 4 makes channels permanently uneditable in
 * the back office — so no edit this class can perform is notifiable. That is the rule working, not
 * an omission.
 */
@Service
public class FieldEditService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_PROFILE_FIELD_EDITED = "profile_field_edited";
  static final String LIST_ADMIN_DIVISION = "admin_division";

  /** See the class Javadoc — narrowed from AD-015's literal wording by product-owner ruling. */
  static final Set<String> EDITABLE_STATUSES = Set.of("submitted", "rejected");

  private final FieldEditRepository fieldEditRepository;
  private final ReferenceCatalog referenceCatalog;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public FieldEditService(
      FieldEditRepository fieldEditRepository,
      ReferenceCatalog referenceCatalog,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.fieldEditRepository = fieldEditRepository;
    this.referenceCatalog = referenceCatalog;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * @param fieldKey an {@link EditableField} name as it appears on the wire
   * @param rawValue the operator's input, trimmed and checked by {@link FieldEditValidator}
   * @return the value as stored, after normalisation
   */
  public String edit(OperatorIdentity identity, UUID profileId, String fieldKey, String rawValue) {
    requireOperatorLevel(identity);

    EditableField field =
        EditableField.parse(fieldKey)
            .orElseThrow(
                () ->
                    new FieldEditRejectedException(
                        "unknown or non-editable field: "
                            + fieldKey
                            + ". Only customer-entered free-text fields may be edited (AD-015,"
                            + " AD-021)."));
    String value = FieldEditValidator.normalise(field, rawValue);

    // Resolved before the transaction opens: a plain read with no side effect, exactly as
    // DataEntryService resolves its reference lookups up front so a failure costs no transaction.
    String sudanCode = sudanCode();

    transactionTemplate.executeWithoutResult(
        status -> {
          // FIRST statement: takes the app.profile row lock before any audit write. See the class
          // Javadoc's lock-ordering note.
          String lockedStatus =
              fieldEditRepository
                  .lockAndReadStatus(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (!EDITABLE_STATUSES.contains(lockedStatus)) {
            throw new FieldNotEditableException(
                "profile "
                    + profileId
                    + " has status "
                    + lockedStatus
                    + "; fields may be edited only while it is submitted or rejected");
          }

          EditabilityFacts facts =
              fieldEditRepository
                  .editabilityFacts(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (!EditableFieldPolicy.editableFor(facts, sudanCode).contains(field)) {
            throw new FieldNotEditableException(
                "field "
                    + field.name()
                    + " is not editable on profile "
                    + profileId
                    + "; editability is derived from this profile's own stored values");
          }

          String previousValue = fieldEditRepository.currentValue(profileId, field).orElse(null);
          Instant now = clock.instant();

          auditEventWriter.append(
              fieldEditedEvent(identity, profileId, field, previousValue, value));

          if (!fieldEditRepository.updateValue(profileId, field, value, now)) {
            // Nothing was written. Reporting success here would tell an operator their correction
            // was filed when the column still holds the old value.
            throw new FieldNotEditableException(
                "field " + field.name() + " has no row to update on profile " + profileId);
          }
          fieldEditRepository.recordEdit(
              profileId, field, previousValue, value, identity.operatorId(), now);
        });

    return value;
  }

  /**
   * The declared cascade root for {@code admin_division} — the country whose administrative
   * divisions are list-picked. Same lookup {@code DataEntryService} uses, and for the same reason:
   * nothing hardcodes {@code 'SD'}.
   */
  private String sudanCode() {
    return referenceCatalog
        .currentRootItemCode(LIST_ADMIN_DIVISION)
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "no root_item_code declared for the current admin_division version"));
  }

  private void requireOperatorLevel(OperatorIdentity identity) {
    if (identity.accessLevel() != OperatorAccessLevel.OPERATOR) {
      throw new AccessLevelRequiredException(
          "operator "
              + identity.operatorId()
              + " has access level "
              + identity.accessLevel()
              + ", which may not edit profile fields");
    }
  }

  /**
   * BL-135: "audit records the field name plus old and new values."
   *
   * <p>The values go in the clear, as every other stage event's {@code previous*} counterparts
   * already do ({@code DataEntryService.stage3Event} and friends) — the audit schema is where this
   * system keeps identity data byte-identical by design, and a redacted edit event would be
   * evidence of nothing.
   */
  private static AuditEvent fieldEditedEvent(
      OperatorIdentity identity,
      UUID profileId,
      EditableField field,
      String previousValue,
      String newValue) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fieldKey", field.name());
    payload.put("fieldNumber", field.fieldNumber());
    payload.put("previousValue", previousValue);
    payload.put("newValue", newValue);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_PROFILE_FIELD_EDITED,
        "operator",
        identity.operatorId(),
        profileId,
        null,
        UUID.randomUUID(),
        CanonicalJson.object(OperatorAuditPayload.withActorRole(identity, payload)));
  }
}
