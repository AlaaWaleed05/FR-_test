package com.sfbank.bayanati.dataentry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.dataentry.domain.CustomerDataSnapshot;
import com.sfbank.bayanati.dataentry.domain.DataEntryRejectedException;
import com.sfbank.bayanati.dataentry.domain.DataEntryRepository;
import com.sfbank.bayanati.dataentry.domain.IncomeSourceRow;
import com.sfbank.bayanati.dataentry.domain.ProfileLock;
import com.sfbank.bayanati.dataentry.domain.ProfileNotEditableException;
import com.sfbank.bayanati.dataentry.domain.Stage3Fields;
import com.sfbank.bayanati.dataentry.domain.Stage6Fields;
import com.sfbank.bayanati.dataentry.domain.UnknownProfileException;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@code DataEntryService}'s orchestration, with every collaborator mocked — no Spring context, no
 * database, no real clock (CLAUDE.md's business-logic testability rule).
 */
class DataEntryServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
  private static final UUID PROFILE_ID = UUID.randomUUID();

  /** Matches the {@code fru.reference.pin-floor-versions-behind} default in production. */
  private static final int PIN_FLOOR_VERSIONS_BEHIND = 2;

  private final DataEntryRepository dataEntryRepository = mock(DataEntryRepository.class);
  private final ReferenceCatalog referenceCatalog = mock(ReferenceCatalog.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final ProfileRepository profileRepository = mock(ProfileRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final DataEntryService service =
      new DataEntryService(
          dataEntryRepository,
          referenceCatalog,
          auditEventWriter,
          profileRepository,
          clock,
          transactionManager,
          PIN_FLOOR_VERSIONS_BEHIND);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
    when(dataEntryRepository.lockAndGetStatus(any()))
        .thenReturn(Optional.of(new ProfileLock("in_progress", false)));
    when(dataEntryRepository.currentCustomerData(any())).thenReturn(emptySnapshot());
    when(dataEntryRepository.currentIncomeSources(any())).thenReturn(List.of());
    // Every list this codebase seeds always has a current version -- default all to version 1.
    when(referenceCatalog.currentVersion(anyString())).thenReturn(1);
    // Default every code to exist; individual tests override for the unknown-code cases.
    when(referenceCatalog.exists(anyString(), eq(1), anyString())).thenReturn(true);
    // Default every pinned version to exist -- pin-specific tests override.
    when(referenceCatalog.versionExists(anyString(), anyInt())).thenReturn(true);
    // Default every admin_division code's parent to "SD" -- satisfies a bare state-level check;
    // locality-specific tests below override this per code.
    when(referenceCatalog.parentCode(eq("admin_division"), anyInt(), anyString()))
        .thenReturn(Optional.of("SD"));
    // admin_division's declared cascade root (V0049, AD-002f, R-045) -- replaces the former
    // SUDAN_CODE constant DataEntryService used to hardcode. "SD" here matches every test's own
    // country-code literal below, exactly as the removed constant did.
    when(referenceCatalog.currentRootItemCode(anyString())).thenReturn(Optional.of("SD"));
  }

  private static CustomerDataSnapshot emptySnapshot() {
    return new CustomerDataSnapshot(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  // ---- Stage 3 ----

  @Test
  void stage3FirstSubmissionWritesColumnsAndAuditEventWithNoPreviousValues() {
    service.submitStage3(
        PROFILE_ID,
        "f",
        "Nubian",
        "SD",
        "single",
        null,
        null,
        null,
        6,
        "SD",
        "11",
        null,
        "Khartoum",
        null,
        null);

    verify(dataEntryRepository).updateStage3(eq(PROFILE_ID), any(Stage3Fields.class), eq(NOW));
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("stage3_data_submitted", captor.getValue().eventType());
    assertEquals(true, captor.getValue().payloadJson().contains("\"previousEthnicity\":null"));
    assertEquals(true, captor.getValue().payloadJson().contains("\"ethnicity\":\"Nubian\""));
    verify(profileRepository).touchLastActivity(PROFILE_ID, NOW);
    verify(profileRepository, never()).reactivateFromAbandoned(any(), any(), anyLong());
  }

  @Test
  void stage3ResubmissionCarriesPreviousValueInAuditPayload() {
    when(dataEntryRepository.currentCustomerData(PROFILE_ID))
        .thenReturn(
            new CustomerDataSnapshot(
                "f",
                "OldEthnicity",
                "SD",
                "single",
                null,
                null,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    service.submitStage3(
        PROFILE_ID,
        "f",
        "NewEthnicity",
        "SD",
        "single",
        null,
        null,
        null,
        6,
        "SD",
        "11",
        null,
        "Khartoum",
        null,
        null);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals(
        true, captor.getValue().payloadJson().contains("\"previousEthnicity\":\"OldEthnicity\""));
    assertEquals(true, captor.getValue().payloadJson().contains("\"ethnicity\":\"NewEthnicity\""));
  }

  @Test
  void stage3UnknownCountryCodeIsRejectedBeforeAnyWrite() {
    when(referenceCatalog.exists(eq("country"), eq(1), eq("ZZ"))).thenReturn(false);

    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "ZZ",
                "single",
                null,
                null,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));

    verify(dataEntryRepository, never()).updateStage3(any(), any(), any());
    verify(auditEventWriter, never()).append(any());
  }

  @Test
  void stage3UnknownProfileIsRejected() {
    when(dataEntryRepository.lockAndGetStatus(PROFILE_ID)).thenReturn(Optional.empty());

    assertThrows(
        UnknownProfileException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "SD",
                "single",
                null,
                null,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));
  }

  @Test
  void stage3TerminalProfileIsRejectedAndAuditedAfterCommit() {
    when(dataEntryRepository.lockAndGetStatus(PROFILE_ID))
        .thenReturn(Optional.of(new ProfileLock("submitted", true)));

    assertThrows(
        ProfileNotEditableException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "SD",
                "single",
                null,
                null,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));

    verify(dataEntryRepository, never()).updateStage3(any(), any(), any());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("data_entry_rejected", captor.getValue().eventType());
  }

  @Test
  void stage3AbandonedProfileIsReactivated() {
    when(dataEntryRepository.lockAndGetStatus(PROFILE_ID))
        .thenReturn(Optional.of(new ProfileLock("abandoned", false)));

    service.submitStage3(
        PROFILE_ID,
        "f",
        "Nubian",
        "SD",
        "single",
        null,
        null,
        null,
        6,
        "SD",
        "11",
        null,
        "Khartoum",
        null,
        null);

    verify(profileRepository).reactivateFromAbandoned(PROFILE_ID, NOW, 1L);
    verify(profileRepository, never()).touchLastActivity(any(), any());
  }

  @Test
  void stage3InvalidSexIsRejected() {
    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "x",
                "Nubian",
                "SD",
                "single",
                null,
                null,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));
  }

  @Test
  void stage3MarriedWithoutSpouseNameIsRejected() {
    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "SD",
                "married",
                null,
                true,
                2,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));
  }

  @Test
  void stage3ChildrenCountTooLargeIsRejected() {
    // children_count is a smallint column with no CHECK bound; without this, an implausible value
    // reaches the UPDATE and fails as a raw 500 instead of a friendly 400 (found by
    // @agent-reviewer).
    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "SD",
                "married",
                "Amina",
                true,
                40000,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));
  }

  @Test
  void stage3SingleWithHasChildrenSentIsRejected() {
    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "SD",
                "single",
                null,
                false,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));
  }

  @Test
  void stage3NonSudanBirthCountryUsesFreeTextAndSkipsAdminDivisionLookup() {
    service.submitStage3(
        PROFILE_ID,
        "f",
        "Nubian",
        "SD",
        "single",
        null,
        null,
        null,
        6,
        "EG",
        null,
        "Cairo",
        "Cairo",
        null,
        null);

    verify(referenceCatalog, never()).exists(eq("admin_division"), anyInt(), anyString());
  }

  @Test
  void stage3WithNoDeclaredAdminDivisionRootFailsClosed() {
    // R-045/AD-002f (V0049): if a list's current version has never had a root declared, the
    // "is this Sudan" check must fail loudly, not silently fall back to a stale literal.
    when(referenceCatalog.currentRootItemCode(anyString())).thenReturn(Optional.empty());

    assertThrows(
        NoSuchElementException.class,
        () ->
            service.submitStage3(
                PROFILE_ID,
                "f",
                "Nubian",
                "SD",
                "single",
                null,
                null,
                null,
                6,
                "SD",
                "11",
                null,
                "Khartoum",
                null,
                null));
  }

  // ---- Stage 4 ----

  @Test
  void stage4UnknownOccupationCodeIsRejected() {
    when(referenceCatalog.exists(eq("occupation"), eq(1), eq("999"))).thenReturn(false);

    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage4(
                PROFILE_ID,
                "999",
                List.of(new IncomeSourceRow("RATIB", true, null)),
                1000L,
                null,
                null));
  }

  @Test
  void stage4TwoPrimaryIncomeSourcesAreRejected() {
    List<IncomeSourceRow> sources =
        List.of(
            new IncomeSourceRow("RATIB", true, null), new IncomeSourceRow("PENSION", true, null));

    assertThrows(
        DataEntryRejectedException.class,
        () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, null, null));
  }

  @Test
  void stage4ZeroPrimaryIncomeSourcesAreRejected() {
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", false, null));

    assertThrows(
        DataEntryRejectedException.class,
        () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, null, null));
  }

  @Test
  void stage4DuplicateIncomeSourceCodesAreRejected() {
    List<IncomeSourceRow> sources =
        List.of(
            new IncomeSourceRow("RATIB", true, null), new IncomeSourceRow("RATIB", false, null));

    assertThrows(
        DataEntryRejectedException.class,
        () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, null, null));
  }

  @Test
  void stage4OtherWithoutTextIsRejected() {
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("OTHER", true, null));

    assertThrows(
        DataEntryRejectedException.class,
        () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, null, null));
  }

  @Test
  void stage4ValidSubmissionWritesOccupationAndReplacesIncomeSources() {
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    service.submitStage4(PROFILE_ID, "86", sources, 1000L, null, null);

    verify(dataEntryRepository).updateStage4Occupation(PROFILE_ID, "86", 1, 1, 1000L, NOW);
    verify(dataEntryRepository).replaceIncomeSources(PROFILE_ID, sources);
  }

  // ---- Stage 5 ----

  @Test
  void stage5NonSudanCountryUsesFreeTextAndSkipsAdminDivisionLookup() {
    service.submitStage5(
        PROFILE_ID,
        "EG",
        null,
        "Cairo Governorate",
        null,
        "Nasr City",
        "Cairo",
        "Area",
        "Street",
        "Block",
        "12",
        null,
        null);

    verify(referenceCatalog, never()).exists(eq("admin_division"), anyInt(), anyString());
    verify(dataEntryRepository).updateStage5(eq(PROFILE_ID), any(), eq(NOW));
  }

  @Test
  void stage5SudanUnknownStateCodeIsRejected() {
    when(referenceCatalog.exists(eq("admin_division"), eq(1), eq("99"))).thenReturn(false);

    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage5(
                PROFILE_ID,
                "SD",
                "99",
                null,
                "1101",
                null,
                "City",
                "Area",
                "Street",
                "Block",
                "12",
                null,
                null));
  }

  @Test
  void stage5LocalityNotChildOfStateIsRejected() {
    when(referenceCatalog.exists(eq("admin_division"), eq(1), eq("11"))).thenReturn(true);
    when(referenceCatalog.parentCode(eq("admin_division"), eq(1), eq("11")))
        .thenReturn(Optional.of("SD"));
    when(referenceCatalog.exists(eq("admin_division"), eq(1), eq("1201"))).thenReturn(true);
    when(referenceCatalog.parentCode(eq("admin_division"), eq(1), eq("1201")))
        .thenReturn(Optional.of("12"));

    assertThrows(
        DataEntryRejectedException.class,
        () ->
            service.submitStage5(
                PROFILE_ID,
                "SD",
                "11",
                null,
                "1201",
                null,
                "City",
                "Area",
                "Street",
                "Block",
                "12",
                null,
                null));
  }

  @Test
  void stage5SudanValidCascadeWrites() {
    when(referenceCatalog.exists(eq("admin_division"), eq(1), eq("11"))).thenReturn(true);
    when(referenceCatalog.parentCode(eq("admin_division"), eq(1), eq("11")))
        .thenReturn(Optional.of("SD"));
    when(referenceCatalog.exists(eq("admin_division"), eq(1), eq("1101"))).thenReturn(true);
    when(referenceCatalog.parentCode(eq("admin_division"), eq(1), eq("1101")))
        .thenReturn(Optional.of("11"));

    service.submitStage5(
        PROFILE_ID,
        "SD",
        "11",
        null,
        "1101",
        null,
        "City",
        "Area",
        "Street",
        "Block",
        "12",
        null,
        null);

    verify(dataEntryRepository).updateStage5(eq(PROFILE_ID), any(), eq(NOW));
  }

  // ---- Stage 6 ----

  @Test
  void stage6ValidSubmissionWrites() {
    service.submitStage6(
        PROFILE_ID,
        "Acme",
        "EG",
        null,
        "Cairo Governorate",
        null,
        "Nasr City",
        "Cairo",
        "Area",
        "Street",
        "Block",
        null,
        null,
        null);

    verify(dataEntryRepository).updateStage6(eq(PROFILE_ID), any(), eq(NOW));
  }

  /**
   * BL-122. The three values the claim can arrive as reach {@code Stage6Fields} unchanged — {@code
   * true}, {@code false} and {@code null} stay distinct all the way to the repository, because the
   * whole point of boxing the field is that "this client did not say" is not the same fact as "the
   * customer attached nothing".
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {true, false})
  void stage6CarriesTheSalaryCertificateClaimVerbatim(Boolean claimed) {
    service.submitStage6(
        PROFILE_ID,
        "Acme",
        "EG",
        null,
        "Cairo Governorate",
        null,
        "Nasr City",
        "Cairo",
        "Area",
        "Street",
        "Block",
        null,
        null,
        claimed);

    ArgumentCaptor<Stage6Fields> fields = ArgumentCaptor.forClass(Stage6Fields.class);
    verify(dataEntryRepository).updateStage6(eq(PROFILE_ID), fields.capture(), eq(NOW));
    assertEquals(claimed, fields.getValue().salaryCertificateClaimed());
  }

  // ---- Stage 7 ----

  @Test
  void stage7ValidSubmissionWritesIdentityType() {
    service.submitStage7(PROFILE_ID, "passport");

    verify(dataEntryRepository).updateStage7(PROFILE_ID, "passport", NOW);
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("stage7_data_submitted", captor.getValue().eventType());
    assertEquals(true, captor.getValue().payloadJson().contains("\"identityType\":\"passport\""));
    assertEquals(true, captor.getValue().payloadJson().contains("\"previousIdentityType\":null"));
  }

  @Test
  void stage7InvalidIdentityTypeIsRejected() {
    assertThrows(
        DataEntryRejectedException.class,
        () -> service.submitStage7(PROFILE_ID, "driving_licence"));

    verify(dataEntryRepository, never()).updateStage7(any(), any(), any());
    verify(auditEventWriter, never()).append(any());
  }

  @Test
  void stage7TerminalProfileIsRejected() {
    when(dataEntryRepository.lockAndGetStatus(PROFILE_ID))
        .thenReturn(Optional.of(new ProfileLock("submitted", true)));

    assertThrows(
        ProfileNotEditableException.class, () -> service.submitStage7(PROFILE_ID, "national_id"));

    verify(dataEntryRepository, never()).updateStage7(any(), any(), any());
  }

  // ---- Version pinning (S4-04, AD-002f §5.3) ----

  @Test
  void pinnedCurrentVersionBehavesExactlyLikeNoPin() {
    // "DataEntryService's existing validation behaviour must be unchanged for a client that pins
    // the current version" -- currentVersion() is mocked to 1 throughout this class, so pinning 1
    // must produce the identical write as the no-pin tests above.
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    service.submitStage4(PROFILE_ID, "86", sources, 1000L, 1, 1);

    verify(dataEntryRepository).updateStage4Occupation(PROFILE_ID, "86", 1, 1, 1000L, NOW);
  }

  @Test
  void pinnedOlderVersionWithinTheFloorIsAccepted() {
    // current = 3, floor = current - 2 = 1 -- pinning exactly the floor must still be accepted.
    when(referenceCatalog.currentVersion("occupation")).thenReturn(3);
    when(referenceCatalog.exists("occupation", 1, "86")).thenReturn(true);
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    service.submitStage4(PROFILE_ID, "86", sources, 1000L, 1, null);

    verify(dataEntryRepository).updateStage4Occupation(PROFILE_ID, "86", 1, 1, 1000L, NOW);
  }

  @Test
  void pinBelowTheFloorIsRejected() {
    // current = 5, floor = current - 2 = 3 -- pinning 1 (two below the floor) must be rejected,
    // proving a stale client cannot pin indefinitely.
    when(referenceCatalog.currentVersion("occupation")).thenReturn(5);
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    assertThrows(
        DataEntryRejectedException.class,
        () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, 1, null));

    verify(dataEntryRepository, never())
        .updateStage4Occupation(any(), any(), anyInt(), anyInt(), anyLong(), any());
  }

  @Test
  void pinNewerThanCurrentIsRejected() {
    // versionExists matches any published row, including one not yet is_current -- a pin ahead of
    // current would validate against a version the server has not activated. exists(occupation, 2,
    // "86") is stubbed true so that if the upper-bound guard were removed, the call would actually
    // SUCCEED -- an indirect "same exception type" assertion could not tell this apart from the
    // floor guard or the versionExists guard rejecting for an unrelated reason, so the message is
    // asserted directly (CLAUDE.md: a direct wrong-value assertion needs no revert-restore proof).
    when(referenceCatalog.currentVersion("occupation")).thenReturn(1);
    when(referenceCatalog.versionExists("occupation", 2)).thenReturn(true);
    when(referenceCatalog.exists("occupation", 2, "86")).thenReturn(true);
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    DataEntryRejectedException ex =
        assertThrows(
            DataEntryRejectedException.class,
            () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, 2, null));
    assertEquals(true, ex.getMessage().contains("newer than the current version"), ex.getMessage());

    verify(dataEntryRepository, never())
        .updateStage4Occupation(any(), any(), anyInt(), anyInt(), anyLong(), any());
  }

  @Test
  void pinForAVersionThatWasNeverPublishedIsRejected() {
    // current = 5, pin = 4 -- deliberately BELOW current (not above), so this isolates the
    // versionExists guard from the newer-than-current guard above: if versionExists were removed,
    // 4 would pass every other check (within the floor, not newer than current) and the call would
    // succeed.
    when(referenceCatalog.currentVersion("occupation")).thenReturn(5);
    when(referenceCatalog.versionExists("occupation", 4)).thenReturn(false);
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    DataEntryRejectedException ex =
        assertThrows(
            DataEntryRejectedException.class,
            () -> service.submitStage4(PROFILE_ID, "86", sources, 1000L, 4, null));
    assertEquals(true, ex.getMessage().contains("does not exist"), ex.getMessage());

    verify(dataEntryRepository, never())
        .updateStage4Occupation(any(), any(), anyInt(), anyInt(), anyLong(), any());
  }

  @Test
  void pinnedVersionOverridesCurrentForCodeExistenceCheck() {
    // The motivating case, unit-scoped: current has moved to 2 and no longer recognises "86", but
    // the pinned version 1 still does -- the pinned version, not current, is what gets validated.
    when(referenceCatalog.currentVersion("occupation")).thenReturn(2);
    when(referenceCatalog.exists("occupation", 2, "86")).thenReturn(false);
    when(referenceCatalog.exists("occupation", 1, "86")).thenReturn(true);
    List<IncomeSourceRow> sources = List.of(new IncomeSourceRow("RATIB", true, null));

    service.submitStage4(PROFILE_ID, "86", sources, 1000L, 1, null);

    verify(dataEntryRepository).updateStage4Occupation(PROFILE_ID, "86", 1, 1, 1000L, NOW);
  }
}
