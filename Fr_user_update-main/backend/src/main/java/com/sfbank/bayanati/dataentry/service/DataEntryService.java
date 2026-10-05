package com.sfbank.bayanati.dataentry.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.dataentry.domain.CustomerDataSnapshot;
import com.sfbank.bayanati.dataentry.domain.DataEntryRejectedException;
import com.sfbank.bayanati.dataentry.domain.DataEntryRepository;
import com.sfbank.bayanati.dataentry.domain.IncomeSourceRow;
import com.sfbank.bayanati.dataentry.domain.ProfileLock;
import com.sfbank.bayanati.dataentry.domain.ProfileNotEditableException;
import com.sfbank.bayanati.dataentry.domain.Stage3Fields;
import com.sfbank.bayanati.dataentry.domain.Stage5Fields;
import com.sfbank.bayanati.dataentry.domain.Stage6Fields;
import com.sfbank.bayanati.dataentry.domain.UnknownProfileException;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stages 3-6 — the four customer-entered data stages (docs/journeys/customer.md).
 *
 * <p>Unlike {@code ContactChannelsService}, nothing here has an external side effect (no OTP, no
 * message send — customer.md Stage 3: "Nothing here has an external side effect"), so a whole
 * submission fits in one transaction: lock the profile, reject unknown/terminal, read the previous
 * values, write the audit event, write the columns.
 *
 * <p><strong>Reference validation happens before any transaction opens</strong> — occupation,
 * country and admin_division checks are plain reads with no side effect, so failing fast on an
 * unknown code costs nothing and keeps the transaction free of work that's about to be thrown away.
 *
 * <p><strong>A terminal profile is rejected the same way {@code ContactChannelsService} rejects a
 * stale re-entry</strong>: the check runs inside the transaction (it needs the row lock), but the
 * rejection's own audit event is written and thrown <em>after</em> the (otherwise empty)
 * transaction commits — writing it inside the callback that then throws would roll the event back
 * with everything else, the exact bug {@code ContactChannelsService}'s Javadoc describes finding
 * under review at S3-07.
 *
 * <p><strong>Every submission is its own audit event, first time or resubmission alike</strong>
 * (customer.md: "each data stage submitted is its own event on the profile's chain ... the
 * permanent record must show that the customer changed their answer, and what it was before"). Each
 * event's payload carries every new value plus a {@code previous<Field>} counterpart, {@code null}
 * on a profile's first stage 3-6 submission.
 *
 * <p><strong>Version pinning (S4-04, AD-002f §5.3).</strong> Each stage 3-6 call carries its own
 * pinned version per list it touches ({@code country}, {@code admin_division}, {@code occupation},
 * {@code income_source}) — there is no server-tracked "session"; the backend only bounds and
 * validates whatever a caller asserts on each call. A {@code null} pin falls back to {@link
 * ReferenceCatalog#currentVersion} — today's behaviour, unchanged — because no mobile client exists
 * yet to send one (OUT OF SCOPE this session): treating "absent" as "not yet opted in" is a no-op
 * for every existing caller. A supplied pin must exist ({@link ReferenceCatalog#versionExists}),
 * must not be newer than {@code currentVersion} (a version {@code versionExists} can see but that
 * is not yet activated), and must not be older than {@code currentVersion - pinFloorVersionsBehind}
 * — see {@link #resolvePinnedVersion}, the one choke point every pinned lookup in this class goes
 * through.
 *
 * <p>{@link #sudanCode()} deliberately stays on {@code currentRootItemCode} (current, not pinned):
 * it is the country-vs-Sudan branch decision made before any {@code admin_division} version is even
 * chosen, the declared root is guarded stable across versions by R-045's FK + trigger (V0049), and
 * pinning it would need a new {@code rootItemCode(listCode, version)} port method for a
 * correspondingly theoretical gap. Recorded here rather than silently assumed, matching V0049's own
 * documented residual-gap style.
 */
@Service
public class DataEntryService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_STAGE3_SUBMITTED = "stage3_data_submitted";
  static final String EVENT_STAGE4_SUBMITTED = "stage4_data_submitted";
  static final String EVENT_STAGE5_SUBMITTED = "stage5_data_submitted";
  static final String EVENT_STAGE6_SUBMITTED = "stage6_data_submitted";
  static final String EVENT_STAGE7_SUBMITTED = "stage7_data_submitted";
  static final String EVENT_DATA_ENTRY_REJECTED = "data_entry_rejected";

  private static final Set<String> IDENTITY_TYPES = Set.of("passport", "national_id");

  static final String STATUS_ABANDONED = "abandoned";

  static final String LIST_OCCUPATION = "occupation";
  static final String LIST_COUNTRY = "country";
  static final String LIST_ADMIN_DIVISION = "admin_division";
  static final String LIST_INCOME_SOURCE = "income_source";

  static final String OTHER_INCOME_CODE = "OTHER";

  private static final Set<String> MARITAL_STATUSES =
      Set.of("single", "married", "divorced", "widowed");

  /**
   * {@code children_count} is a {@code smallint} column (V0006) with no CHECK bound. Without an
   * application-level ceiling, an implausible value passes every check here and fails as a raw
   * {@code 22003 smallint out of range} 500 at the {@code UPDATE} instead of a friendly 400 — found
   * by {@code @agent-reviewer}.
   */
  private static final int MAX_CHILDREN_COUNT = 30;

  private final DataEntryRepository dataEntryRepository;
  private final ReferenceCatalog referenceCatalog;
  private final AuditEventWriter auditEventWriter;
  private final ProfileRepository profileRepository;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final int pinFloorVersionsBehind;

  public DataEntryService(
      DataEntryRepository dataEntryRepository,
      ReferenceCatalog referenceCatalog,
      AuditEventWriter auditEventWriter,
      ProfileRepository profileRepository,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${fru.reference.pin-floor-versions-behind:2}") int pinFloorVersionsBehind) {
    this.dataEntryRepository = dataEntryRepository;
    this.referenceCatalog = referenceCatalog;
    this.auditEventWriter = auditEventWriter;
    this.profileRepository = profileRepository;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.pinFloorVersionsBehind = pinFloorVersionsBehind;
  }

  /**
   * @param hasChildren {@code null} iff {@code maritalStatus} is {@code single}
   * @param childrenCount required iff {@code hasChildren} is {@code true}
   * @param birthStateCode/birthStateText exactly one populated, depending on {@code
   *     birthCountryCode}
   */
  public String submitStage3(
      UUID profileId,
      String sexDeclared,
      String ethnicity,
      String countryOfResidenceCode,
      String maritalStatus,
      String spouseName,
      Boolean hasChildren,
      Integer childrenCount,
      int educationLevel,
      String birthCountryCode,
      String birthStateCode,
      String birthStateText,
      String birthCityText,
      Integer countryListVersion,
      Integer adminDivisionListVersion) {
    validateSexDeclared(sexDeclared);
    validateMaritalStatus(maritalStatus, spouseName, hasChildren, childrenCount);
    if (educationLevel < 1 || educationLevel > 7) {
      throw new DataEntryRejectedException("educationLevel must be between 1 and 7");
    }
    int countryVersion = resolvePinnedVersion(LIST_COUNTRY, countryListVersion);
    int countryOfResidenceVersion =
        validateExists(LIST_COUNTRY, countryVersion, countryOfResidenceCode);
    int birthCountryVersion = validateExists(LIST_COUNTRY, countryVersion, birthCountryCode);
    BirthState birthState =
        resolveBirthState(
            birthCountryCode, birthStateCode, birthStateText, adminDivisionListVersion);

    Instant now = clock.instant();
    Stage3Fields fields =
        new Stage3Fields(
            sexDeclared,
            ethnicity,
            countryOfResidenceCode,
            countryOfResidenceVersion,
            maritalStatus,
            spouseName,
            hasChildren,
            childrenCount,
            educationLevel,
            birthCountryCode,
            birthCountryVersion,
            birthState.code(),
            birthState.text(),
            birthCityText,
            birthState.adminDivVersion());

    runStageSubmission(
        profileId,
        "stage3",
        now,
        previous -> {
          long eventId = auditEventWriter.append(stage3Event(profileId, fields, previous));
          dataEntryRepository.updateStage3(profileId, fields, now);
          return eventId;
        });
    return "stage3";
  }

  private BirthState resolveBirthState(
      String birthCountryCode,
      String birthStateCode,
      String birthStateText,
      Integer adminDivisionListVersion) {
    if (sudanCode().equals(birthCountryCode)) {
      requireNonBlank(birthStateCode, "birthStateCode");
      int version = resolvePinnedVersion(LIST_ADMIN_DIVISION, adminDivisionListVersion);
      validateAdminDivisionState(version, birthStateCode);
      return new BirthState(birthStateCode, null, version);
    }
    requireNonBlank(birthStateText, "birthStateText");
    return new BirthState(null, birthStateText, null);
  }

  private record BirthState(String code, String text, Integer adminDivVersion) {}

  /**
   * {@code sex_declared} has a DB-level CHECK (V0006) — validated here too so a bad value gets a
   * friendly 400 instead of a raw constraint-violation 500.
   */
  private void validateSexDeclared(String sexDeclared) {
    if (!"m".equals(sexDeclared) && !"f".equals(sexDeclared)) {
      throw new DataEntryRejectedException("sexDeclared must be 'm' or 'f'");
    }
  }

  /**
   * Validates {@code maritalStatus} against the DB's own CHECK set (V0006) and enforces
   * customer.md's conditional-field table: {@code spouseName} only for {@code married}; {@code
   * hasChildren} not asked at all for {@code single}; {@code childrenCount} only when {@code
   * hasChildren} is {@code true}.
   */
  private void validateMaritalStatus(
      String maritalStatus, String spouseName, Boolean hasChildren, Integer childrenCount) {
    if (!MARITAL_STATUSES.contains(maritalStatus)) {
      throw new DataEntryRejectedException("unknown maritalStatus: " + maritalStatus);
    }
    boolean married = "married".equals(maritalStatus);
    boolean single = "single".equals(maritalStatus);

    if (married) {
      requireNonBlank(spouseName, "spouseName");
    } else if (spouseName != null) {
      throw new DataEntryRejectedException(
          "spouseName must not be sent unless maritalStatus is married");
    }

    if (single) {
      if (hasChildren != null || childrenCount != null) {
        throw new DataEntryRejectedException(
            "hasChildren/childrenCount must not be sent when maritalStatus is single");
      }
      return;
    }
    if (hasChildren == null) {
      throw new DataEntryRejectedException("hasChildren is required for this maritalStatus");
    }
    if (hasChildren) {
      if (childrenCount == null || childrenCount < 1 || childrenCount > MAX_CHILDREN_COUNT) {
        throw new DataEntryRejectedException(
            "childrenCount is required and must be between 1 and "
                + MAX_CHILDREN_COUNT
                + " when hasChildren is true");
      }
    } else if (childrenCount != null) {
      throw new DataEntryRejectedException(
          "childrenCount must not be sent when hasChildren is false");
    }
  }

  private void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new DataEntryRejectedException(fieldName + " is required for this selection");
    }
  }

  /**
   * The declared cascade root for {@code admin_division}'s current version — {@code
   * ref.reference_list_version.root_item_code}, guarded by a composite FK onto the country list
   * plus a deferred constraint trigger (V0049, AD-002f, R-045). Replaces the former {@code
   * SUDAN_CODE} constant, which asserted the same value with nothing tying it to the schema.
   * Resolved per call, not cached, matching this class's existing no-caching pattern for {@link
   * ReferenceCatalog#currentVersion}.
   */
  private String sudanCode() {
    return referenceCatalog
        .currentRootItemCode(LIST_ADMIN_DIVISION)
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "no root_item_code declared for the current admin_division version"));
  }

  /**
   * The one choke point every pinned reference-list lookup in this class resolves through — see the
   * class Javadoc's "Version pinning" section.
   *
   * @param pinnedVersion the caller-supplied pin, or {@code null} to fall back to {@link
   *     ReferenceCatalog#currentVersion} (today's behaviour, unchanged)
   */
  private int resolvePinnedVersion(String listCode, Integer pinnedVersion) {
    int current = referenceCatalog.currentVersion(listCode);
    if (pinnedVersion == null) {
      return current;
    }
    if (!referenceCatalog.versionExists(listCode, pinnedVersion)) {
      throw new DataEntryRejectedException(
          "pinned version " + pinnedVersion + " for " + listCode + " does not exist");
    }
    // versionExists matches ANY published row, including one not yet is_current -- a pin newer
    // than current would validate against a version the server has not activated yet. "Not older
    // than a floor" only bounds staleness; this bounds the other direction.
    if (pinnedVersion > current) {
      throw new DataEntryRejectedException(
          "pinned version "
              + pinnedVersion
              + " for "
              + listCode
              + " is newer than the current version ("
              + current
              + ")");
    }
    int floor = current - pinFloorVersionsBehind;
    if (pinnedVersion < floor) {
      throw new DataEntryRejectedException(
          "pinned version "
              + pinnedVersion
              + " for "
              + listCode
              + " is older than the accepted floor ("
              + floor
              + "); current is "
              + current);
    }
    return pinnedVersion;
  }

  private int validateExists(String listCode, int version, String itemCode) {
    if (!referenceCatalog.exists(listCode, version, itemCode)) {
      throw new DataEntryRejectedException("unknown " + listCode + " code: " + itemCode);
    }
    return version;
  }

  private void validateAdminDivisionState(int version, String stateCode) {
    if (!referenceCatalog.exists(LIST_ADMIN_DIVISION, version, stateCode)) {
      throw new DataEntryRejectedException("unknown admin_division code: " + stateCode);
    }
    Optional<String> parent = referenceCatalog.parentCode(LIST_ADMIN_DIVISION, version, stateCode);
    if (parent.isEmpty() || !sudanCode().equals(parent.get())) {
      throw new DataEntryRejectedException(
          "admin_division code " + stateCode + " is not a Sudan state");
    }
  }

  private void validateAdminDivisionLocality(String localityCode, String stateCode, int version) {
    if (!referenceCatalog.exists(LIST_ADMIN_DIVISION, version, localityCode)) {
      throw new DataEntryRejectedException("unknown admin_division code: " + localityCode);
    }
    Optional<String> parent =
        referenceCatalog.parentCode(LIST_ADMIN_DIVISION, version, localityCode);
    if (parent.isEmpty() || !stateCode.equals(parent.get())) {
      throw new DataEntryRejectedException(
          "admin_division locality " + localityCode + " is not within state " + stateCode);
    }
  }

  /** Shared by stages 5 and 6 — Sudan uses admin_division codes, otherwise free text. */
  private AddressCascade resolveAddressCascade(
      String countryCode,
      String stateCode,
      String stateText,
      String localityCode,
      String localityText,
      Integer adminDivisionListVersion) {
    if (sudanCode().equals(countryCode)) {
      requireNonBlank(stateCode, "stateCode");
      requireNonBlank(localityCode, "localityCode");
      int version = resolvePinnedVersion(LIST_ADMIN_DIVISION, adminDivisionListVersion);
      validateAdminDivisionState(version, stateCode);
      validateAdminDivisionLocality(localityCode, stateCode, version);
      return new AddressCascade(stateCode, null, localityCode, null, version);
    }
    requireNonBlank(stateText, "stateText");
    requireNonBlank(localityText, "localityText");
    return new AddressCascade(null, stateText, null, localityText, null);
  }

  private record AddressCascade(
      String stateCode,
      String stateText,
      String localityCode,
      String localityText,
      Integer adminDivVersion) {}

  public String submitStage4(
      UUID profileId,
      String occupationCode,
      List<IncomeSourceRow> incomeSources,
      long monthlyExpensesSdg,
      Integer occupationListVersion,
      Integer incomeSourceListVersion) {
    int occupationVersion = resolvePinnedVersion(LIST_OCCUPATION, occupationListVersion);
    validateExists(LIST_OCCUPATION, occupationVersion, occupationCode);
    int incomeSourceVersion = resolvePinnedVersion(LIST_INCOME_SOURCE, incomeSourceListVersion);
    validateIncomeSources(incomeSources, incomeSourceVersion);
    if (monthlyExpensesSdg < 0) {
      throw new DataEntryRejectedException("monthlyExpensesSdg must not be negative");
    }

    Instant now = clock.instant();
    runStageSubmission(
        profileId,
        "stage4",
        now,
        previous -> {
          List<IncomeSourceRow> previousIncome =
              dataEntryRepository.currentIncomeSources(profileId);
          long eventId =
              auditEventWriter.append(
                  stage4Event(
                      profileId,
                      occupationCode,
                      incomeSources,
                      monthlyExpensesSdg,
                      previous,
                      previousIncome));
          dataEntryRepository.updateStage4Occupation(
              profileId,
              occupationCode,
              occupationVersion,
              incomeSourceVersion,
              monthlyExpensesSdg,
              now);
          dataEntryRepository.replaceIncomeSources(profileId, incomeSources);
          return eventId;
        });
    return "stage4";
  }

  private void validateIncomeSources(List<IncomeSourceRow> sources, int version) {
    if (sources.isEmpty()) {
      throw new DataEntryRejectedException("at least one income source is required");
    }
    Set<String> seen = new HashSet<>();
    int primaryCount = 0;
    for (IncomeSourceRow source : sources) {
      if (!seen.add(source.sourceCode())) {
        throw new DataEntryRejectedException(
            "duplicate income source code: " + source.sourceCode());
      }
      if (!referenceCatalog.exists(LIST_INCOME_SOURCE, version, source.sourceCode())) {
        throw new DataEntryRejectedException("unknown income_source code: " + source.sourceCode());
      }
      if (OTHER_INCOME_CODE.equals(source.sourceCode())
          && (source.otherText() == null || source.otherText().isBlank())) {
        throw new DataEntryRejectedException("income source OTHER requires otherText");
      }
      if (source.primary()) {
        primaryCount++;
      }
    }
    if (primaryCount != 1) {
      throw new DataEntryRejectedException("exactly one income source must be marked primary");
    }
  }

  public String submitStage5(
      UUID profileId,
      String countryCode,
      String stateCode,
      String stateText,
      String localityCode,
      String localityText,
      String city,
      String area,
      String street,
      String block,
      String houseNumber,
      Integer countryListVersion,
      Integer adminDivisionListVersion) {
    int countryVersion = resolvePinnedVersion(LIST_COUNTRY, countryListVersion);
    validateExists(LIST_COUNTRY, countryVersion, countryCode);
    AddressCascade cascade =
        resolveAddressCascade(
            countryCode,
            stateCode,
            stateText,
            localityCode,
            localityText,
            adminDivisionListVersion);

    Instant now = clock.instant();
    runStageSubmission(
        profileId,
        "stage5",
        now,
        previous -> {
          Stage5Fields fields =
              new Stage5Fields(
                  countryCode,
                  countryVersion,
                  cascade.stateCode(),
                  cascade.stateText(),
                  cascade.localityCode(),
                  cascade.localityText(),
                  city,
                  area,
                  street,
                  block,
                  houseNumber,
                  cascade.adminDivVersion());
          long eventId = auditEventWriter.append(stage5Event(profileId, fields, previous));
          dataEntryRepository.updateStage5(profileId, fields, now);
          return eventId;
        });
    return "stage5";
  }

  public String submitStage6(
      UUID profileId,
      String employer,
      String countryCode,
      String stateCode,
      String stateText,
      String localityCode,
      String localityText,
      String city,
      String area,
      String street,
      String block,
      Integer countryListVersion,
      Integer adminDivisionListVersion,
      Boolean salaryCertificateAttached) {
    int countryVersion = resolvePinnedVersion(LIST_COUNTRY, countryListVersion);
    validateExists(LIST_COUNTRY, countryVersion, countryCode);
    AddressCascade cascade =
        resolveAddressCascade(
            countryCode,
            stateCode,
            stateText,
            localityCode,
            localityText,
            adminDivisionListVersion);

    Instant now = clock.instant();
    runStageSubmission(
        profileId,
        "stage6",
        now,
        previous -> {
          Stage6Fields fields =
              new Stage6Fields(
                  employer,
                  countryCode,
                  countryVersion,
                  cascade.stateCode(),
                  cascade.stateText(),
                  cascade.localityCode(),
                  cascade.localityText(),
                  city,
                  area,
                  street,
                  block,
                  cascade.adminDivVersion(),
                  salaryCertificateAttached);
          long eventId = auditEventWriter.append(stage6Event(profileId, fields, previous));
          dataEntryRepository.updateStage6(profileId, fields, now);
          return eventId;
        });
    return "stage6";
  }

  /**
   * Journey Stage 7 — identity document type (customer.md: "The last stage before Uqudo enters, and
   * the last with free back-navigation"). One field, no reference-list lookup: {@code
   * identity_type}'s only legal values are the ones {@code app.profile_customer_data}'s own CHECK
   * constraint accepts (V0006).
   */
  public String submitStage7(UUID profileId, String identityType) {
    if (!IDENTITY_TYPES.contains(identityType)) {
      throw new DataEntryRejectedException("identityType must be 'passport' or 'national_id'");
    }

    Instant now = clock.instant();
    runStageSubmission(
        profileId,
        "stage7",
        now,
        previous -> {
          long eventId = auditEventWriter.append(stage7Event(profileId, identityType, previous));
          dataEntryRepository.updateStage7(profileId, identityType, now);
          return eventId;
        });
    return "stage7";
  }

  /**
   * Shared frame for every stage: lock the profile, reject an unknown or terminal one, otherwise
   * hand the previous snapshot to {@code writer}, which writes this stage's own audit event and
   * column update and returns the event's id — used to reactivate an abandoned profile, or just to
   * bump {@code last_activity_at} otherwise.
   *
   * <p>A terminal rejection is recorded <em>after</em> this (otherwise empty) transaction commits,
   * never inside the callback that would then throw — see this class's Javadoc and {@code
   * ContactChannelsService}'s identical reasoning.
   */
  private void runStageSubmission(UUID profileId, String stage, Instant now, StageWriter writer) {
    boolean[] terminalRejected = {false};
    String[] rejectedStatus = {null};

    transactionTemplate.executeWithoutResult(
        status -> {
          ProfileLock lock =
              dataEntryRepository
                  .lockAndGetStatus(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (lock.terminal()) {
            terminalRejected[0] = true;
            rejectedStatus[0] = lock.status();
            return;
          }

          CustomerDataSnapshot previous = dataEntryRepository.currentCustomerData(profileId);
          long eventId = writer.write(previous);

          if (STATUS_ABANDONED.equals(lock.status())) {
            profileRepository.reactivateFromAbandoned(profileId, now, eventId);
          } else {
            profileRepository.touchLastActivity(profileId, now);
          }
        });

    if (terminalRejected[0]) {
      auditEventWriter.append(dataEntryRejectedEvent(profileId, stage, rejectedStatus[0]));
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached status " + rejectedStatus[0]);
    }
  }

  @FunctionalInterface
  private interface StageWriter {
    long write(CustomerDataSnapshot previous);
  }

  private static AuditEvent dataEntryRejectedEvent(UUID profileId, String stage, String status) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stage", stage);
    payload.put("profileStatus", status);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_DATA_ENTRY_REJECTED,
        "customer",
        null,
        profileId,
        profileId,
        UUID.randomUUID(),
        CanonicalJson.object(payload));
  }

  private static AuditEvent stage3Event(
      UUID profileId, Stage3Fields fields, CustomerDataSnapshot previous) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sexDeclared", fields.sexDeclared());
    payload.put("previousSexDeclared", previous.sexDeclared());
    payload.put("ethnicity", fields.ethnicity());
    payload.put("previousEthnicity", previous.ethnicity());
    payload.put("countryOfResidenceCode", fields.countryOfResidenceCode());
    payload.put("previousCountryOfResidenceCode", previous.countryOfResidenceCode());
    payload.put("maritalStatus", fields.maritalStatus());
    payload.put("previousMaritalStatus", previous.maritalStatus());
    payload.put("spouseName", fields.spouseName());
    payload.put("previousSpouseName", previous.spouseName());
    payload.put("hasChildren", fields.hasChildren());
    payload.put("previousHasChildren", previous.hasChildren());
    payload.put("childrenCount", fields.childrenCount());
    payload.put("previousChildrenCount", previous.childrenCount());
    payload.put("educationLevel", fields.educationLevel());
    payload.put("previousEducationLevel", previous.educationLevel());
    payload.put("birthCountryCode", fields.birthCountryCode());
    payload.put("previousBirthCountryCode", previous.birthCountryCode());
    payload.put("birthStateCode", fields.birthStateCode());
    payload.put("previousBirthStateCode", previous.birthStateCode());
    payload.put("birthStateText", fields.birthStateText());
    payload.put("previousBirthStateText", previous.birthStateText());
    payload.put("birthCityText", fields.birthCityText());
    payload.put("previousBirthCityText", previous.birthCityText());
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_STAGE3_SUBMITTED,
        "customer",
        null,
        profileId,
        profileId,
        UUID.randomUUID(),
        CanonicalJson.object(payload));
  }

  private static AuditEvent stage4Event(
      UUID profileId,
      String occupationCode,
      List<IncomeSourceRow> incomeSources,
      long monthlyExpensesSdg,
      CustomerDataSnapshot previous,
      List<IncomeSourceRow> previousIncome) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("occupationCode", occupationCode);
    payload.put("previousOccupationCode", previous.occupationCode());
    payload.put("monthlyExpensesSdg", monthlyExpensesSdg);
    payload.put("previousMonthlyExpensesSdg", previous.monthlyExpensesSdg());
    payload.put("incomeSourceCodes", joinedCodes(incomeSources));
    payload.put("previousIncomeSourceCodes", joinedCodes(previousIncome));
    payload.put("primaryIncomeSourceCode", primaryCode(incomeSources));
    payload.put("previousPrimaryIncomeSourceCode", primaryCode(previousIncome));
    payload.put("otherIncomeText", otherText(incomeSources));
    payload.put("previousOtherIncomeText", otherText(previousIncome));
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_STAGE4_SUBMITTED,
        "customer",
        null,
        profileId,
        profileId,
        UUID.randomUUID(),
        CanonicalJson.object(payload));
  }

  private static String joinedCodes(List<IncomeSourceRow> sources) {
    if (sources.isEmpty()) {
      return null;
    }
    return sources.stream()
        .map(IncomeSourceRow::sourceCode)
        .sorted()
        .reduce((a, b) -> a + "," + b)
        .orElse(null);
  }

  private static String primaryCode(List<IncomeSourceRow> sources) {
    return sources.stream()
        .filter(IncomeSourceRow::primary)
        .map(IncomeSourceRow::sourceCode)
        .findFirst()
        .orElse(null);
  }

  private static String otherText(List<IncomeSourceRow> sources) {
    return sources.stream()
        .filter(s -> OTHER_INCOME_CODE.equals(s.sourceCode()))
        .map(IncomeSourceRow::otherText)
        .findFirst()
        .orElse(null);
  }

  private static AuditEvent stage5Event(
      UUID profileId, Stage5Fields fields, CustomerDataSnapshot previous) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("homeCountryCode", fields.homeCountryCode());
    payload.put("previousHomeCountryCode", previous.homeCountryCode());
    payload.put("homeStateCode", fields.homeStateCode());
    payload.put("previousHomeStateCode", previous.homeStateCode());
    payload.put("homeStateText", fields.homeStateText());
    payload.put("previousHomeStateText", previous.homeStateText());
    payload.put("homeLocalityCode", fields.homeLocalityCode());
    payload.put("previousHomeLocalityCode", previous.homeLocalityCode());
    payload.put("homeLocalityText", fields.homeLocalityText());
    payload.put("previousHomeLocalityText", previous.homeLocalityText());
    payload.put("homeCity", fields.homeCity());
    payload.put("previousHomeCity", previous.homeCity());
    payload.put("homeArea", fields.homeArea());
    payload.put("previousHomeArea", previous.homeArea());
    payload.put("homeStreet", fields.homeStreet());
    payload.put("previousHomeStreet", previous.homeStreet());
    payload.put("homeBlock", fields.homeBlock());
    payload.put("previousHomeBlock", previous.homeBlock());
    payload.put("homeHouseNo", fields.homeHouseNo());
    payload.put("previousHomeHouseNo", previous.homeHouseNo());
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_STAGE5_SUBMITTED,
        "customer",
        null,
        profileId,
        profileId,
        UUID.randomUUID(),
        CanonicalJson.object(payload));
  }

  private static AuditEvent stage6Event(
      UUID profileId, Stage6Fields fields, CustomerDataSnapshot previous) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("employerName", fields.employerName());
    payload.put("previousEmployerName", previous.employerName());
    payload.put("workCountryCode", fields.workCountryCode());
    payload.put("previousWorkCountryCode", previous.workCountryCode());
    payload.put("workStateCode", fields.workStateCode());
    payload.put("previousWorkStateCode", previous.workStateCode());
    payload.put("workStateText", fields.workStateText());
    payload.put("previousWorkStateText", previous.workStateText());
    payload.put("workLocalityCode", fields.workLocalityCode());
    payload.put("previousWorkLocalityCode", previous.workLocalityCode());
    payload.put("workLocalityText", fields.workLocalityText());
    payload.put("previousWorkLocalityText", previous.workLocalityText());
    payload.put("workCity", fields.workCity());
    payload.put("previousWorkCity", previous.workCity());
    payload.put("workArea", fields.workArea());
    payload.put("previousWorkArea", previous.workArea());
    payload.put("workStreet", fields.workStreet());
    payload.put("previousWorkStreet", previous.workStreet());
    payload.put("workBlock", fields.workBlock());
    payload.put("previousWorkBlock", previous.workBlock());
    // BL-122. No "previous" counterpart, unlike every field above it: this is a monotonic claim
    // rather than part of the full replace, so there is no prior value it overwrites. Recorded
    // because it is the customer's own assertion about a file the bank may not hold, and the chain
    // is where assertions of that kind belong.
    payload.put("salaryCertificateAttached", fields.salaryCertificateClaimed());
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_STAGE6_SUBMITTED,
        "customer",
        null,
        profileId,
        profileId,
        UUID.randomUUID(),
        CanonicalJson.object(payload));
  }

  private static AuditEvent stage7Event(
      UUID profileId, String identityType, CustomerDataSnapshot previous) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("identityType", identityType);
    payload.put("previousIdentityType", previous.identityType());
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_STAGE7_SUBMITTED,
        "customer",
        null,
        profileId,
        profileId,
        UUID.randomUUID(),
        CanonicalJson.object(payload));
  }
}
