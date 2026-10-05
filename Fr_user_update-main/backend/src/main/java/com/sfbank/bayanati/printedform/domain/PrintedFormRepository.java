package com.sfbank.bayanati.printedform.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the print needs from the database that the operator tier's own read does not already
 * carry: the image bytes, the salary certificate, the reference-list versions PINNED to the
 * profile, the provenance-matrix version, and the write and read-back of a stored print.
 *
 * <p>A port, in a {@code domain} package rather than beside its JDBC adapter — CLAUDE.md's rule,
 * and the reason is that the service above it is business logic a plain JUnit test must be able to
 * exercise with no database.
 */
public interface PrintedFormRepository {

  /**
   * The ONE {@code app.artifact_ref.kind} a printed form is stored under.
   *
   * <p>There were two until AD-022 (S9-01): {@code printed_form} and {@code
   * printed_form_attributed}, and the pair was derived from {@code PrintedFormVariant.values()}.
   * The variant choice is gone, so this is a single constant — and it must stay in step with
   * V0072's {@code artifact_ref_kind_check}, which no longer admits the attributed spelling. A
   * mismatch is a constraint violation at print time, not at start-up.
   */
  String PRINTED_FORM_KIND = "printed_form";

  /**
   * The bytes for each image slot that has a committed artifact. A slot with no row is simply
   * absent from the map and the form prints «غير متاح» in its box.
   *
   * <p>Bytes come through {@code app.artifact_read()}, which verifies each against its stored
   * checksum and RAISES on a mismatch rather than returning something that changed underneath. A
   * corrupted identity image must fail the print, not print as a missing one.
   *
   * <p>Deliberately NOT filtered by content type, unlike the operator image endpoint. The renderer
   * refuses any render FOP could not complete (S8-35), so bytes FOP cannot decode fail the whole
   * print — which is strictly safer than dropping them and printing an absence the bank never
   * claimed. An allow-list here would convert a corrupt portrait into a false «غير متاح».
   */
  Map<PrintedFormImageSlot, byte[]> images(UUID profileId);

  /**
   * The customer's own salary certificate, passed through untouched. Empty when the profile has no
   * committed one — which is the ordinary case, since it is optional and gates nothing.
   */
  Optional<StoredArtifact> salaryCertificate(UUID profileId);

  /**
   * The reference-list versions this PROFILE used, from {@code ref.profile_reference_version},
   * keyed by list code.
   *
   * <p>CLAUDE.md: "The list version used for a submission is recorded on the profile." This reads
   * that record; it never asks which version is current. A reprint in a year must say what the
   * original said, and resolving against today's list is exactly how it would stop doing so.
   *
   * <p>Four lists are pinned there ({@code occupation}, {@code admin_division}, {@code
   * income_source}, {@code country}), written at submission by {@code SubmissionService}. {@code
   * branch} and {@code education_level} are NOT pinned by any existing writer, so those two resolve
   * against the current version — stated here rather than left for a reader to discover, and
   * harmless today because neither list has ever had a second version.
   */
  Map<String, Integer> pinnedReferenceVersions(UUID profileId);

  /**
   * The form-field NUMBERS an operator has keyed on this profile, driving the «معدَّل» marker.
   *
   * <p>Reads {@code app.profile_field_edit} (V0073), whose own table comment names this as its
   * second consumer — S9-02 built the storage and deliberately left the marker to S9-03. One row
   * per edited field, a later edit of the same field overwriting the earlier one, so this is
   * current state and not a log. The tamper-evident history is the audit chain, which this
   * deliberately does NOT read: the printed-form path stays inside {@code app}, and crossing into
   * {@code audit} to render a document would put a PII-bearing join in the rendering path (V0073's
   * own reasoning).
   *
   * <p><strong>Why numbers rather than the {@code field_key} that is the primary key.</strong>
   * {@code field_number} is denormalised onto every row for exactly this: V0073 says it is there
   * "so a reader of this table alone can map a row to the bank's paper form without resolving
   * field_key against Java source". Taking the number keeps this feature from depending on {@code
   * operator.domain.EditableField}, which is another feature's enum.
   *
   * <p>Needs no migration and no new grant — V0073 already grants {@code SELECT} to {@code
   * fru_app}, and the query is served by the primary key's own leading column.
   */
  Set<Integer> editedFieldNumbers(UUID profileId);

  /**
   * The provenance-matrix version governing this profile, recording it on first use.
   *
   * <p>{@code app.profile_provenance_matrix_version} (V0027/V0029) has had no writer since it was
   * created, and V0027's own comment says so: "Written once by whatever backend code later resolves
   * a profile's fields (no such code exists yet)". This is that code. Nothing else in the system
   * resolves a profile's fields against {@code field-provenance.md} — the journey writes columns,
   * it does not decide which source governs which field — so the print is the first and only
   * resolver, and the moment it runs is the moment the question has an answer.
   *
   * <p>Recorded ONCE and never updated: the table's grant is {@code SELECT, INSERT} with no {@code
   * UPDATE}, and its primary key is the profile. A reprint after the matrix moves to version 3
   * therefore reads back the version the first print pinned, which is what "a reprint in a year
   * says the same thing" asks for.
   *
   * @return the version now recorded against this profile
   */
  int recordProvenanceMatrixVersion(UUID profileId);

  /**
   * Stores one rendered print as a new {@code app.artifact_ref} row and answers with its id.
   *
   * <p>Every print is its own artifact and nothing is superseded (ticket 05 decision 2), so this
   * always INSERTs — there is no upsert and no unique index to conflict with.
   */
  UUID storePrintedForm(UUID profileId, String kind, byte[] bytes, Instant now);

  /**
   * One previously stored print, for a re-download. Empty for every kind of absence — unknown
   * profile, unknown artifact, an artifact belonging to a DIFFERENT profile, a kind that is not
   * {@link #PRINTED_FORM_KIND}, a non-committed row, a row whose body has been purged. One answer
   * for all of them, because telling them apart lets a caller walk artifact ids across customers
   * and learn which are real.
   */
  Optional<StoredArtifact> findPrintedForm(UUID profileId, UUID artifactId);

  /** An artifact's bytes together with the content type declared when it was stored. */
  record StoredArtifact(UUID artifactId, String contentType, byte[] bytes) {}
}
