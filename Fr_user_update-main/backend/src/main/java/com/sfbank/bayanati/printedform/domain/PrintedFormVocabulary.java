package com.sfbank.bayanati.printedform.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The fixed Arabic words the form uses for CHECK-constrained columns and structural enums — sex,
 * marital status, identity type, yes/no — plus the list codes the reference-backed fields resolve
 * against.
 *
 * <p>These are NOT reference data and hardcoding them is not a breach of CLAUDE.md's "reference
 * lists are never hardcoded" rule, which names occupations, branches, administrative divisions,
 * income sources and rejection reason codes. These are database CHECK constraints (V0006, V0008,
 * V0023): adding a marital status means a migration, not a list publication. The back office states
 * the same distinction for the same values in {@code backoffice/src/profiles/detailLabels.ts}.
 *
 * <p><strong>There is no longer a divergence from that file, and the note claiming one was stale
 * for two days.</strong> This comment used to record that the back office said «بطاقة وطنية» where
 * the form said «البطاقة القومية», and asked for the difference to be put to the product owner.
 * BL-151 closed it at S9-02 by fixing the back office, and {@code detailLabels.ts} has said
 * «البطاقة القومية» ever since — the same string {@link #IDENTITY_TYPE} holds. Corrected at S9-03,
 * having survived the session that was supposed to remove it. Recorded rather than silently deleted
 * because a comment freezing an old truth is self-confirming: it stops the next reader checking,
 * which is exactly how it lasted.
 */
public final class PrintedFormVocabulary {

  private PrintedFormVocabulary() {}

  /** {@code ref.reference_list} codes, exactly as the seed migrations declare them. */
  

  public static final String LIST_OCCUPATION = "occupation";
  public static final String LIST_ADMIN_DIVISION = "admin_division";
  public static final String LIST_INCOME_SOURCE = "income_source";
  public static final String LIST_EDUCATION_LEVEL = "education_level";
  public static final String LIST_COUNTRY = "country";

  /** {@code app.profile_customer_data.sex_declared} / {@code app.registry_result.sex_registry}. */
  private static final Map<String, String> SEX = Map.of("m", "ذكر", "f", "أنثى");

  /**
   * {@code app.profile_customer_data.marital_status} (V0006), masculine.
   *
   * <p>Paired with {@link #MARITAL_STATUS_FEMININE} since S9-03. The approved page 2 prints
   * «متزوجة» for a woman, and a bank form that addresses a married woman as «متزوج» is a visible
   * defect on a document she may be handed — product-owner ruling, 2026-09-16. The agreement
   * follows the same sex the spouse-name row already follows: the registry's, falling back to the
   * declared value.
   */
  private static final Map<String, String> MARITAL_STATUS =
      Map.of(
          "single", "أعزب",
          "married", "متزوج",
          "divorced", "مطلّق",
          "widowed", "أرمل");

  /** The same four states in feminine agreement. */
  private static final Map<String, String> MARITAL_STATUS_FEMININE =
      Map.of(
          "single", "عزباء",
          "married", "متزوجة",
          "divorced", "مطلّقة",
          "widowed", "أرملة");

  /** {@code app.profile_customer_data.identity_type} (V0006). */
  private static final Map<String, String> IDENTITY_TYPE =
      Map.of("passport", "جواز سفر", "national_id", "البطاقة القومية");

  public static final String YES = "نعم";
  public static final String NO = "لا";

  /**
   * «أساسي» — marks the one income source flagged primary.
   *
   * <p>The word changed at S9-03 to the approved page 2's, which reads «وظيفة — أساسي». It was
   * «رئيسي», under a doc-comment that said «الرئيسي» — the constant and its own description had
   * disagreed since they were written, and neither matched the design.
   */
  public static final String PRIMARY_SUFFIX = "أساسي";

  // The profile-level provenance word «رقمي»/«يدوي» was HERE, and it is gone with the identity
  // band that printed it (AD-022 ruling (a), S9-03). Deleted rather than left unused: the approved
  // form has no place for it, AD-022 ruling 1 means no profile can be `manual` any more, and
  // ruling (g) settles what a pre-ruling one should print by flushing them all. The back office
  // keeps its own PROVENANCE_LABELS_AR, which the status list still reads.

  public static String sex(String value) {
    return lookup(SEX, value);
  }

  /**
   * @param female whether the customer is female, so the state agrees with her. Unknown sex takes
   *     the masculine form, which is the language's own unmarked default rather than a guess.
   */
  public static String maritalStatus(String value, boolean female) {
    return lookup(female ? MARITAL_STATUS_FEMININE : MARITAL_STATUS, value);
  }

  public static String identityType(String value) {
    return lookup(IDENTITY_TYPE, value);
  }

  public static String yesNo(Boolean value) {
    return value == null ? null : value ? YES : NO;
  }

  /**
   * Unrecognised values come back UNCHANGED, never null and never lowercased. {@code
   * scan_result.sex_on_document} is unconstrained free text from Uqudo, unlike the CHECK-bound
   * columns, so a value outside the dictionary is shown as received — the same correction the back
   * office's {@code sexLabel} took at review, for the same reason: a filing document must not
   * silently case-mutate a value the bank actually holds.
   */
  private static String lookup(Map<String, String> dictionary, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return dictionary.getOrDefault(value.toLowerCase(Locale.ROOT), value);
  }
}
