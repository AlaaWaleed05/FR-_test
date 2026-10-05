package com.sfbank.bayanati.printedform.domain;

import java.util.Optional;

/**
 * The Arabic label for one reference-coded value, already pinned to the version the PROFILE used.
 *
 * <p>This exists so the assembler can stay a plain function. {@code ReferenceCatalog#find} takes a
 * {@code (listCode, version, itemCode)} triple and reaches the database; resolving the version is a
 * separate question from resolving the label, and it is the half with the rule attached — CLAUDE.md
 * requires the list version used for a submission to be recorded on the profile and read from
 * there, never fetched as "latest". Whoever implements this has already answered that question, so
 * an assembler holding one of these cannot get the version wrong, because it never sees one.
 *
 * <p>An empty answer means the code did not resolve — an item deactivated since, a list version
 * that no longer carries it, or a code that was never in that list. The assembler prints the raw
 * code in that case rather than «غير متاح»: the value exists and the bank recorded it, and a filing
 * document that silently turns a real occupation code into "not available" is worse than one that
 * shows a number a branch officer can look up. Same posture as the back office's own {@code
 * resolveLabel}, which falls back to the code for the same reason.
 */
@FunctionalInterface
public interface ReferenceLabels {

  /**
   * @param listCode one of {@code occupation}, {@code branch}, {@code admin_division}, {@code
   *     income_source}, {@code education_level}, {@code country}
   * @param itemCode the code stored on the profile
   * @return the Arabic label, or empty if this code does not resolve in the pinned version
   */
  Optional<String> label(String listCode, String itemCode);
}
