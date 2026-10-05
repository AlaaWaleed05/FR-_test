package com.sfbank.bayanati.reference.domain;

import java.util.Optional;

/**
 * Read-only access to {@code ref.reference_item} / {@code ref.reference_list_version} — the
 * server-supplied, version-checked lists CLAUDE.md requires every coded field to be validated
 * against (occupations, branches, administrative divisions, income sources, rejection reasons).
 *
 * <p>Shared infrastructure, not scoped to one feature — occupation, country and admin_division are
 * each consumed by more than one data-entry stage — so this lives beside {@code corebanking}/
 * {@code audit}/{@code messaging} rather than inside {@code dataentry}.
 *
 * <p>Every method validates against a specific {@code (list_code, version)} pair. This port never
 * decides which version is "right" for a caller — {@link #currentVersion} is offered as a building
 * block, not a default baked into {@link #exists}/{@link #parentCode}, so a future caller that
 * needs to validate against a client-supplied (possibly older, cached) version can do so without
 * this port changing shape.
 */
public interface ReferenceCatalog {

  /**
   * The version currently marked {@code is_current} for {@code listCode} — the {@code
   * ref_one_current} unique index (V0011) guarantees at most one.
   *
   * @throws java.util.NoSuchElementException if {@code listCode} has no current version (every list
   *     this codebase seeds always has exactly one; this is a configuration error, not a user input
   *     to validate)
   */
  int currentVersion(String listCode);

  /**
   * @return {@code true} iff {@code version} exists in {@code ref.reference_list_version} for
   *     {@code listCode} — any published version, not only the current one. Added at S4-04 (AD-002f
   *     §5.3) so a caller validating a client-pinned version can tell "an older but real version"
   *     apart from "a version that was never published," before applying its own floor.
   */
  boolean versionExists(String listCode, int version);

  /**
   * @return {@code true} only for an <em>active</em> item — a superseded duplicate (occupation
   *     codes 133/139/135, hidden by {@code is_active = false}) never validates as existing,
   *     matching the picker's own "not offered" behaviour
   */
  boolean exists(String listCode, int version, String itemCode);

  /**
   * @return the item's {@code parent_code}, empty if the item doesn't exist or has no parent (a
   *     list root, or a non-hierarchical list)
   */
  Optional<String> parentCode(String listCode, int version, String itemCode);

  /**
   * @return the item's display fields ({@link ReferenceItemDetail}), empty if it doesn't exist.
   *     Added at S4-01 for the operator side, which needs more than existence — a rejection
   *     reason's internal label for the status-history view, and its customer-facing message (in
   *     {@code extra}) to render into the rejection notification.
   */
  Optional<ReferenceItemDetail> find(String listCode, int version, String itemCode);

  /**
   * The item whose {@code extra->>'alpha3'} matches, for a list keyed on something else.
   *
   * <p>Added at S9-03 for the printed form's fields 4 and 48. Both carry what the MRZ printed,
   * which is ISO 3166-1 <strong>alpha-3</strong> («SDN»), while the country list's {@code
   * item_code} is alpha-2 («SD»), so {@link #find} cannot resolve them at all and the form printed
   * the raw code.
   *
   * <p><strong>The mapping is server-supplied, not invented.</strong> V0022 seeds {@code
   * {"alpha3":"SDN"}} into {@code extra} on all 249 country rows — which is what BL-157 was closed
   * by discovering, after a comment in the assembler had asserted for two sessions that no such
   * mapping existed. The back office already resolves this way client-side ({@code
   * backoffice/src/api/reference.ts}); this is the same data reached from the server, so print and
   * screen agree.
   *
   * <p>Deliberately keyed on the same {@code (listCode, version)} as {@link #find}, so a reprint
   * resolves against the version the PROFILE pinned rather than today's list.
   *
   * <p>No index on {@code extra} and none added: the country list is 249 rows for one version, and
   * a print resolves at most two codes.
   *
   * @param alpha3 matched case-insensitively; the seeded values are upper case
   */
  Optional<ReferenceItemDetail> findByAlpha3(String listCode, int version, String alpha3);

  /**
   * The declared cascade root for {@code listCode}'s <em>current</em> version — {@code
   * ref.reference_list_version.root_item_code} (V0049, AD-002f, R-045), guarded by a composite FK
   * onto the country list plus a deferred constraint trigger asserting a hierarchical list's one
   * parentless row actually carries this code.
   *
   * <p>Added at S4-03 so callers stop hardcoding {@code 'SD'} — {@code DataEntryService} is the
   * first caller, replacing its own {@code SUDAN_CODE} constant with this lookup against {@code
   * admin_division}.
   *
   * @return the declared root, empty for a flat list (which has no root) or a list whose current
   *     version predates this column and has never had one set
   */
  Optional<String> currentRootItemCode(String listCode);
}
