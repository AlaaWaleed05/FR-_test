package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileListFilter;
import com.sfbank.bayanati.operator.domain.ProfileListPage;
import com.sfbank.bayanati.operator.domain.ProfileListResult;
import com.sfbank.bayanati.operator.domain.ProfileListSortField;
import com.sfbank.bayanati.operator.domain.SortOrder;
import com.sfbank.bayanati.operator.service.OperatorProfileListService;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** operator.md's profile list — the operator's primary working surface. */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class ProfileListController {

  private final OperatorProfileListService profileListService;

  public ProfileListController(OperatorProfileListService profileListService) {
    this.profileListService = profileListService;
  }

  @GetMapping
  public ProfileListResponse search(
      OperatorIdentity identity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String provenance,
      
      @RequestParam(required = false) String rejectionReasonCode,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "SUBMITTED_AT") String sortField,
      @RequestParam(required = false, defaultValue = "DESC") String sortOrder,
      @RequestParam(required = false, defaultValue = "1") int page,
      @RequestParam(required = false, defaultValue = "20") int pageSize) {

    ProfileListFilter filter =
        new ProfileListFilter(
            status,
            provenance,
            
            rejectionReasonCode,
            parseInstant(submittedFrom, "submittedFrom"),
            parseInstant(submittedTo, "submittedTo"),
            q);

    ProfileListResult result =
        profileListService.search(
            identity,
            filter,
            parseSortField(sortField),
            parseSortOrder(sortOrder),
            parsePage(page, pageSize));

    return ProfileListResponse.from(result);
  }

  private static Instant parseInstant(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException notIso) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is not a valid ISO-8601 instant: " + value);
    }
  }

  private static ProfileListSortField parseSortField(String value) {
    try {
      return ProfileListSortField.valueOf(value);
    } catch (IllegalArgumentException notAField) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown sortField: " + value);
    }
  }

  private static SortOrder parseSortOrder(String value) {
    try {
      return SortOrder.valueOf(value);
    } catch (IllegalArgumentException notAnOrder) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown sortOrder: " + value);
    }
  }

  private static ProfileListPage parsePage(int page, int pageSize) {
    try {
      return new ProfileListPage(page, pageSize);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
  }
}
