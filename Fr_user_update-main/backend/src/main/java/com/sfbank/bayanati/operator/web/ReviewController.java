package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.InvalidRejectionReasonException;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileNotReviewableException;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.operator.service.OperatorReviewService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** operator.md "Review — approve or reject" and "Re-approving a rejected profile". */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class ReviewController {

  private final OperatorReviewService reviewService;

  public ReviewController(OperatorReviewService reviewService) {
    this.reviewService = reviewService;
  }

  @PostMapping("/{profileId}/approve")
  public ReviewResponse approve(OperatorIdentity identity, @PathVariable String profileId) {
    UUID id = parseProfileId(profileId);
    try {
      return ReviewResponse.from(reviewService.approve(identity, id));
    } catch (UnknownProfileException unknown) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
    } catch (AccessLevelRequiredException forbidden) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbidden.getMessage());
    } catch (ProfileNotReviewableException notReviewable) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notReviewable.getMessage());
    }
  }

  @PostMapping("/{profileId}/reject")
  public ReviewResponse reject(
      OperatorIdentity identity,
      @PathVariable String profileId,
      @RequestBody RejectRequest request) {
    UUID id = parseProfileId(profileId);
    try {
      return ReviewResponse.from(
          reviewService.reject(identity, id, request.reasonCode(), request.internalNote()));
    } catch (UnknownProfileException unknown) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
    } catch (AccessLevelRequiredException forbidden) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbidden.getMessage());
    } catch (InvalidRejectionReasonException invalidReason) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidReason.getMessage());
    } catch (ProfileNotReviewableException notReviewable) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notReviewable.getMessage());
    }
  }

  private static UUID parseProfileId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is not a valid UUID");
    }
  }
}
