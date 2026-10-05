package com.sfbank.bayanati.operator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.EditabilityFacts;
import com.sfbank.bayanati.operator.domain.EditableField;
import com.sfbank.bayanati.operator.domain.FieldEditRejectedException;
import com.sfbank.bayanati.operator.domain.FieldEditRepository;
import com.sfbank.bayanati.operator.domain.FieldNotEditableException;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * BL-135 / AD-015. Mirrors {@code DataEntryServiceTest}'s shape: a mocked {@code
 * PlatformTransactionManager}, so this stays a plain JUnit test with no Spring context, no database
 * and no clock.
 *
 * <p>What a mocked transaction manager CANNOT prove is anything about rollback — the live proofs
 * for the derived provenance, the stored row and the real status guard are in {@code
 * FieldEditIntegrationTest} against the real database.
 */
class FieldEditServiceTest {

  private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");
  private static final String SUDAN = "SD";

  private static final OperatorIdentity OPERATOR =
      new OperatorIdentity("faheem.operator", OperatorAccessLevel.OPERATOR, "operator");
  private static final OperatorIdentity VIEWER =
      new OperatorIdentity("nadia.viewer", OperatorAccessLevel.VIEWER, "viewer");

  private final FieldEditRepository fieldEditRepository = mock(FieldEditRepository.class);
  private final ReferenceCatalog referenceCatalog = mock(ReferenceCatalog.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final FieldEditService service =
      new FieldEditService(
          fieldEditRepository, referenceCatalog, auditEventWriter, clock, transactionManager);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
    when(referenceCatalog.currentRootItemCode(anyString())).thenReturn(Optional.of(SUDAN));
    when(fieldEditRepository.lockAndReadStatus(PROFILE_ID)).thenReturn(Optional.of("submitted"));
    when(fieldEditRepository.editabilityFacts(PROFILE_ID))
        .thenReturn(Optional.of(sudanMarriedFacts()));
    when(fieldEditRepository.currentValue(eq(PROFILE_ID), any())).thenReturn(Optional.of("قديم"));
    when(fieldEditRepository.updateValue(eq(PROFILE_ID), any(), anyString(), any()))
        .thenReturn(true);
  }

  // -------------------------------------------------------------------------------- happy path

  @Test
  void storesTheTrimmedValueAndRecordsThePerFieldProvenanceRow() {
    String stored = service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "  الثورة الحارة ٢٤  ");

    assertEquals("الثورة الحارة ٢٤", stored);
    verify(fieldEditRepository)
        .updateValue(PROFILE_ID, EditableField.HOME_AREA, "الثورة الحارة ٢٤", NOW);
    verify(fieldEditRepository)
        .recordEdit(
            PROFILE_ID,
            EditableField.HOME_AREA,
            "قديم",
            "الثورة الحارة ٢٤",
            "faheem.operator",
            NOW);
  }

  /** BL-135: "audit records the field name plus old and new values." */
  @Test
  void writesAnAuditEventCarryingTheFieldTheOldValueAndTheNewOne() {
    service.edit(OPERATOR, PROFILE_ID, "EMPLOYER_NAME", "الجهاز المركزي للإحصاء");

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    AuditEvent event = captor.getValue();

    assertEquals("profile", event.chainKind());
    assertEquals(PROFILE_ID.toString(), event.chainSubject());
    assertEquals("profile_field_edited", event.eventType());
    assertEquals("operator", event.actorKind());
    assertEquals("faheem.operator", event.actorId());

    String payload = event.payloadJson();
    assertTrue(payload.contains("\"fieldKey\":\"EMPLOYER_NAME\""), payload);
    assertTrue(payload.contains("\"fieldNumber\":27"), payload);
    assertTrue(payload.contains("\"previousValue\":\"قديم\""), payload);
    assertTrue(payload.contains("\"newValue\":\"الجهاز المركزي للإحصاء\""), payload);
    // R-054's compensating control: an admin's edit and an operator's must stay distinguishable.
    assertTrue(payload.contains("\"actorRole\":\"operator\""), payload);
  }

  /**
   * The lock-ordering invariant {@code ReviewRepository}'s Javadoc records: the {@code app.profile}
   * row lock BEFORE the audit-chain lock that {@code AuditEventWriter#append} takes. Two writers
   * taking them in opposite orders deadlocked live at S4-01 (PostgreSQL 40P01).
   */
  @Test
  void takesTheProfileRowLockBeforeWritingTheAuditEvent() {
    service.edit(OPERATOR, PROFILE_ID, "HOME_CITY", "أم درمان");

    InOrder inOrder = Mockito.inOrder(fieldEditRepository, auditEventWriter);
    inOrder.verify(fieldEditRepository).lockAndReadStatus(PROFILE_ID);
    inOrder.verify(auditEventWriter).append(any());
  }

  @Test
  void aNullPreviousValueIsCarriedThroughRatherThanInvented() {
    when(fieldEditRepository.currentValue(eq(PROFILE_ID), any())).thenReturn(Optional.empty());

    service.edit(OPERATOR, PROFILE_ID, "HOME_BLOCK", "12");

    verify(fieldEditRepository)
        .recordEdit(PROFILE_ID, EditableField.HOME_BLOCK, null, "12", "faheem.operator", NOW);
  }

  // ------------------------------------------------------------------------------------ bounds

  /** AD-015 names "viewers cannot edit" as one of the bounds that make a data-entry tier safe. */
  @Test
  void aViewerMayNotEditAnything() {
    assertThrows(
        AccessLevelRequiredException.class,
        () -> service.edit(VIEWER, PROFILE_ID, "HOME_AREA", "الثورة"));

    verify(fieldEditRepository, never()).updateValue(any(), any(), anyString(), any());
    verify(auditEventWriter, never()).append(any());
  }

  /**
   * Product-owner ruling 2026-09-16. Not caution: {@code app.profile_customer_data} is
   * device-authoritative and every customer stage write is a full-row replace, so an edit to an
   * {@code in_progress} profile is silently destroyed by the customer's next submission.
   */
  @Test
  void refusesEveryStatusExceptSubmittedAndRejected() {
    for (String status :
        new String[] {
          "in_progress",
          "awaiting_registry",
          "blocked_scan",
          "blocked_liveness",
          "abandoned",
          "approved",
          "terminated_registry_mismatch"
        }) {
      when(fieldEditRepository.lockAndReadStatus(PROFILE_ID)).thenReturn(Optional.of(status));

      FieldNotEditableException thrown =
          assertThrows(
              FieldNotEditableException.class,
              () -> service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "الثورة"),
              status + " must not be editable");
      assertTrue(thrown.getMessage().contains(status), thrown.getMessage());
    }
    verify(fieldEditRepository, never()).updateValue(any(), any(), anyString(), any());
  }

  /** {@code rejected} is genuinely "before approved" — a rejected profile can still be approved. */
  @Test
  void allowsEditingARejectedProfile() {
    when(fieldEditRepository.lockAndReadStatus(PROFILE_ID)).thenReturn(Optional.of("rejected"));

    assertEquals("الثورة", service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "الثورة"));
  }

  /**
   * The server re-derives editability rather than trusting the client. A hidden «تعديل» chip is
   * presentation; this is the control.
   */
  @Test
  void refusesAFieldThisProfileDoesNotExpose() {
    // A Sudan home address: fields 36 and 37 are list-picked, so there is no free text to edit.
    FieldNotEditableException thrown =
        assertThrows(
            FieldNotEditableException.class,
            () -> service.edit(OPERATOR, PROFILE_ID, "HOME_STATE_TEXT", "Cairo Governorate"));

    assertTrue(thrown.getMessage().contains("HOME_STATE_TEXT"), thrown.getMessage());
    verify(fieldEditRepository, never()).updateValue(any(), any(), anyString(), any());
    verify(auditEventWriter, never()).append(any());
  }

  /** The same field on a profile that DOES expose it succeeds — proving the refusal is derived. */
  @Test
  void acceptsTheSameFieldOnAProfileWhoseAddressIsOutsideSudan() {
    when(fieldEditRepository.editabilityFacts(PROFILE_ID))
        .thenReturn(
            Optional.of(new EditabilityFacts(true, "married", SUDAN, "EG", SUDAN, true, null)));

    assertEquals(
        "Cairo Governorate",
        service.edit(OPERATOR, PROFILE_ID, "HOME_STATE_TEXT", "Cairo Governorate"));
  }

  @Test
  void refusesAnUnknownFieldKeyAsABadRequest() {
    FieldEditRejectedException thrown =
        assertThrows(
            FieldEditRejectedException.class,
            () -> service.edit(OPERATOR, PROFILE_ID, "PHONE_NUMBER", "+249900000001"));

    assertTrue(thrown.getMessage().contains("PHONE_NUMBER"), thrown.getMessage());
    verify(fieldEditRepository, never()).lockAndReadStatus(any());
  }

  @Test
  void refusesABlankValueBeforeOpeningATransaction() {
    assertThrows(
        FieldEditRejectedException.class,
        () -> service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "   "));

    verify(fieldEditRepository, never()).lockAndReadStatus(any());
    verify(transactionManager, never()).getTransaction(any());
  }

  @Test
  void reportsAnUnknownProfileAsNotFound() {
    when(fieldEditRepository.lockAndReadStatus(PROFILE_ID)).thenReturn(Optional.empty());

    assertThrows(
        UnknownProfileException.class,
        () -> service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "الثورة"));
  }

  /**
   * An update that matched no row must not be reported as success. Telling an operator their
   * correction was filed while the column still holds the old value is the one failure this path
   * must never produce.
   */
  @Test
  void refusesWhenTheUpdateMatchedNoRow() {
    when(fieldEditRepository.updateValue(eq(PROFILE_ID), any(), anyString(), any()))
        .thenReturn(false);

    assertThrows(
        FieldNotEditableException.class,
        () -> service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "الثورة"));

    verify(fieldEditRepository, never())
        .recordEdit(any(), any(), any(), anyString(), anyString(), any());
  }

  /** Nothing hardcodes 'SD' — the declared cascade root is read, as every other caller reads it. */
  @Test
  void resolvesTheCascadeRootFromTheReferenceCatalogue() {
    service.edit(OPERATOR, PROFILE_ID, "HOME_AREA", "الثورة");

    verify(referenceCatalog).currentRootItemCode("admin_division");
  }

  private static EditabilityFacts sudanMarriedFacts() {
    return new EditabilityFacts(true, "married", SUDAN, SUDAN, SUDAN, true, null);
  }
}
