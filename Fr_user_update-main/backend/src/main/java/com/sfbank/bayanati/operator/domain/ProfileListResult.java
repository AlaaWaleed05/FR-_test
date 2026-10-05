package com.sfbank.bayanati.operator.domain;

import java.util.List;

/**
 * @param total the full match count under the current filter, ignoring paging — Ant Design's Table
 *     needs this for server-side pagination (BL-015).
 */
public record ProfileListResult(List<ProfileSummary> rows, long total) {}
