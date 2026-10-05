package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.ProfileListResult;
import java.util.List;

/**
 * @param total BL-015's server-side pagination total, ignoring paging.
 */
public record ProfileListResponse(List<ProfileSummaryResponse> rows, long total) {

  public static ProfileListResponse from(ProfileListResult result) {
    return new ProfileListResponse(
        result.rows().stream().map(ProfileSummaryResponse::from).toList(), result.total());
  }
}
