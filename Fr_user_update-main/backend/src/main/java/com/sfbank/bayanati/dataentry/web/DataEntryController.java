package com.sfbank.bayanati.dataentry.web;

import com.sfbank.bayanati.dataentry.domain.DataEntryRejectedException;
import com.sfbank.bayanati.dataentry.domain.IncomeSourceRow;
import com.sfbank.bayanati.dataentry.domain.ProfileNotEditableException;
import com.sfbank.bayanati.dataentry.domain.UnknownProfileException;
import com.sfbank.bayanati.dataentry.service.DataEntryService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stages 3-7's five endpoints (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/data-entry")
public class DataEntryController {

  /** Short coded fields — reference-list item codes, ISO country codes, enum-ish values. */
  static final int MAX_CODE_LENGTH = 32;

  /** Free-text fields — names, address lines. Long enough for a full Arabic name or a street. */
  static final int MAX_TEXT_LENGTH = 256;

  /** {@code bigint} comfortably holds far more than any real SDG amount needs. */
  static final int MAX_MONEY_DIGITS = 15;

  private final DataEntryService dataEntryService;

  public DataEntryController(DataEntryService dataEntryService) {
    this.dataEntryService = dataEntryService;
  }

  @PostMapping("/stage3")
  public DataEntryResponse stage3(@RequestBody Stage3Request request) {
    UUID profileId = parseProfileId(request.profileId());
    String sexDeclared = clean(request.sexDeclared(), "sexDeclared", MAX_CODE_LENGTH);
    String ethnicity = clean(request.ethnicity(), "ethnicity", MAX_TEXT_LENGTH);
    String countryOfResidenceCode =
        clean(request.countryOfResidenceCode(), "countryOfResidenceCode", MAX_CODE_LENGTH);
    String maritalStatus = clean(request.maritalStatus(), "maritalStatus", MAX_CODE_LENGTH);
    String spouseName = cleanOptional(request.spouseName(), "spouseName", MAX_TEXT_LENGTH);
    Integer educationLevel = requireNonNull(request.educationLevel(), "educationLevel");
    String birthCountryCode =
        clean(request.birthCountryCode(), "birthCountryCode", MAX_CODE_LENGTH);
    String birthStateCode =
        cleanOptional(request.birthStateCode(), "birthStateCode", MAX_CODE_LENGTH);
    String birthStateText =
        cleanOptional(request.birthStateText(), "birthStateText", MAX_TEXT_LENGTH);
    String birthCityText = clean(request.birthCityText(), "birthCityText", MAX_TEXT_LENGTH);

    String stage =
        run(
            () ->
                dataEntryService.submitStage3(
                    profileId,
                    sexDeclared,
                    ethnicity,
                    countryOfResidenceCode,
                    maritalStatus,
                    spouseName,
                    request.hasChildren(),
                    request.childrenCount(),
                    educationLevel,
                    birthCountryCode,
                    birthStateCode,
                    birthStateText,
                    birthCityText,
                    request.countryListVersion(),
                    request.adminDivisionListVersion()));
    return new DataEntryResponse(profileId.toString(), stage);
  }

  @PostMapping("/stage4")
  public DataEntryResponse stage4(@RequestBody Stage4Request request) {
    UUID profileId = parseProfileId(request.profileId());
    String occupationCode = clean(request.occupationCode(), "occupationCode", MAX_CODE_LENGTH);
    List<IncomeSourceRow> incomeSources = cleanIncomeSources(request.incomeSources());
    long monthlyExpensesSdg = parseMonthlyExpenses(request.monthlyExpensesSdg());

    String stage =
        run(
            () ->
                dataEntryService.submitStage4(
                    profileId,
                    occupationCode,
                    incomeSources,
                    monthlyExpensesSdg,
                    request.occupationListVersion(),
                    request.incomeSourceListVersion()));
    return new DataEntryResponse(profileId.toString(), stage);
  }

  @PostMapping("/stage5")
  public DataEntryResponse stage5(@RequestBody Stage5Request request) {
    UUID profileId = parseProfileId(request.profileId());
    String countryCode = clean(request.countryCode(), "countryCode", MAX_CODE_LENGTH);
    String stateCode = cleanOptional(request.stateCode(), "stateCode", MAX_CODE_LENGTH);
    String stateText = cleanOptional(request.stateText(), "stateText", MAX_TEXT_LENGTH);
    String localityCode = cleanOptional(request.localityCode(), "localityCode", MAX_CODE_LENGTH);
    String localityText = cleanOptional(request.localityText(), "localityText", MAX_TEXT_LENGTH);
    String city = clean(request.city(), "city", MAX_TEXT_LENGTH);
    String area = clean(request.area(), "area", MAX_TEXT_LENGTH);
    String street = clean(request.street(), "street", MAX_TEXT_LENGTH);
    String block = clean(request.block(), "block", MAX_TEXT_LENGTH);
    String houseNumber = clean(request.houseNumber(), "houseNumber", MAX_TEXT_LENGTH);

    String stage =
        run(
            () ->
                dataEntryService.submitStage5(
                    profileId,
                    countryCode,
                    stateCode,
                    stateText,
                    localityCode,
                    localityText,
                    city,
                    area,
                    street,
                    block,
                    houseNumber,
                    request.countryListVersion(),
                    request.adminDivisionListVersion()));
    return new DataEntryResponse(profileId.toString(), stage);
  }

  @PostMapping("/stage6")
  public DataEntryResponse stage6(@RequestBody Stage6Request request) {
    UUID profileId = parseProfileId(request.profileId());
    String employer = clean(request.employer(), "employer", MAX_TEXT_LENGTH);
    String countryCode = clean(request.countryCode(), "countryCode", MAX_CODE_LENGTH);
    String stateCode = cleanOptional(request.stateCode(), "stateCode", MAX_CODE_LENGTH);
    String stateText = cleanOptional(request.stateText(), "stateText", MAX_TEXT_LENGTH);
    String localityCode = cleanOptional(request.localityCode(), "localityCode", MAX_CODE_LENGTH);
    String localityText = cleanOptional(request.localityText(), "localityText", MAX_TEXT_LENGTH);
    String city = clean(request.city(), "city", MAX_TEXT_LENGTH);
    String area = clean(request.area(), "area", MAX_TEXT_LENGTH);
    String street = clean(request.street(), "street", MAX_TEXT_LENGTH);
    String block = clean(request.block(), "block", MAX_TEXT_LENGTH);

    String stage =
        run(
            () ->
                dataEntryService.submitStage6(
                    profileId,
                    employer,
                    countryCode,
                    stateCode,
                    stateText,
                    localityCode,
                    localityText,
                    city,
                    area,
                    street,
                    block,
                    request.countryListVersion(),
                    request.adminDivisionListVersion(),
                    request.salaryCertificateAttached()));
    return new DataEntryResponse(profileId.toString(), stage);
  }

  @PostMapping("/stage7")
  public DataEntryResponse stage7(@RequestBody Stage7Request request) {
    UUID profileId = parseProfileId(request.profileId());
    String identityType = clean(request.identityType(), "identityType", MAX_CODE_LENGTH);

    String stage = run(() -> dataEntryService.submitStage7(profileId, identityType));
    return new DataEntryResponse(profileId.toString(), stage);
  }

  /**
   * {@code 404} for an unknown {@code profileId}, {@code 409} for a terminal profile, {@code 400}
   * for any other business-rule rejection — see {@link DataEntryService}'s Javadoc for why a
   * terminal rejection is still audited even though it surfaces as an exception here.
   */
  private static String run(Supplier<String> call) {
    try {
      return call.get();
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    } catch (ProfileNotEditableException notEditable) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notEditable.getMessage());
    } catch (DataEntryRejectedException rejected) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.getMessage());
    }
  }

  private static List<IncomeSourceRow> cleanIncomeSources(List<IncomeSourceInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "incomeSources must contain at least one entry");
    }
    return inputs.stream()
        .map(
            input -> {
              String code = clean(input.code(), "incomeSources[].code", MAX_CODE_LENGTH);
              String otherText =
                  cleanOptional(input.otherText(), "incomeSources[].otherText", MAX_TEXT_LENGTH);
              return new IncomeSourceRow(code, input.primary(), otherText);
            })
        .toList();
  }

  private static long parseMonthlyExpenses(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monthlyExpensesSdg is required");
    }
    if (trimmed.length() > MAX_MONEY_DIGITS
        || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "monthlyExpensesSdg must be ASCII digits only, no sign or point");
    }
    try {
      return Long.parseLong(trimmed);
    } catch (NumberFormatException overflow) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monthlyExpensesSdg is too large");
    }
  }

  private static UUID parseProfileId(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is not a valid UUID");
    }
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return value;
  }

  /** A required field: blank, oversized or containing a control character all fail 400. */
  private static String clean(String value, String fieldName, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return validated(trimmed, fieldName, maxLength);
  }

  /**
   * An optional field: blank or absent means "not supplied"; present still obeys {@link #clean}'s
   * bounds.
   */
  private static String cleanOptional(String value, String fieldName, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return validated(trimmed, fieldName, maxLength);
  }

  private static String validated(String trimmed, String fieldName, int maxLength) {
    if (trimmed.length() > maxLength) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is longer than " + maxLength + " characters");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (Character.isISOControl(trimmed.charAt(i))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, fieldName + " contains a control character");
      }
    }
    return trimmed;
  }
}
