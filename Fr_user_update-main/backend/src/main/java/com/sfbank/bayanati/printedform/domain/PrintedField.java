package com.sfbank.bayanati.printedform.domain;

import java.util.List;
import java.util.Objects;

/**
 * One row of the printed form: a label, one or more values, and the markers the form puts beside
 * them.
 *
 * @param number the field's number in {@code docs/journeys/field-provenance.md}. Carried so a
 *     reader of this code, or of a test failure, can find the row that governs it without guessing
 *     from the label. Not printed.
 * @param label the Arabic label, exactly as field-provenance.md gives it. Two of them are
 *     deliberately counter-intuitive — field 9 «النوع» is the bank's label for SEX and field 10
 *     «الجنس» is its label for ETHNICITY — and the form prints the bank's labels, not the literal
 *     reading.
 * @param values EXACTLY ONE value, always. Never empty — an absent field carries one {@link
 *     PrintedValue#absent} rather than no value — and never more than one, since the 2026-09-14
 *     ruling gave each of the previously dual-source fields a single source. Kept as a list rather
 *     than a bare value so the shape does not have to change if a field ever needs two again.
 * @param edited whether an operator has keyed this field since the customer submitted it — one row
 *     in {@code app.profile_field_edit} (V0073), read per field. Drives the «معدَّل» marker.
 *     Renamed from {@code manuallyEntered} at S9-03, because AD-022 deleted manual completion and
 *     the old name named a thing that no longer happens.
 * @param editedBy the operator who keyed it, or null. Still never rendered — the approved form
 *     names the printing operator once, in the page footer, and does not attribute individual rows.
 *     Kept because it is the answer to "who", it costs nothing to carry, and the footer's name is a
 *     different question.
 */
public record PrintedField(
    int number, String label, List<PrintedValue> values, boolean edited, String editedBy) {

  /**
   * The marker printed beside an edited value.
   *
   * <p>«معدَّل», not «يدوي». The old word said the value was entered by hand on a manually
   * completed profile; this one says a value the CUSTOMER supplied was later corrected by an
   * operator, which is the only kind of hand-keying AD-022 leaves. It lives here rather than in
   * {@code FoDocumentWriter} so the word and the flag that drives it are declared together.
   */
  public static final String EDITED_MARKER = "معدَّل";

  /**
   * Field 51, «مستندات الهوية». Not a field this form has ever printed and not one it may print:
   * {@code field-provenance.md} marks it "Not collected" — there is no separate upload of a
   * national ID or passport, because the Uqudo scan satisfies them — so the row could only ever
   * have said that nothing was collected. Product-owner ruling, 2026-09-14, narrowing ticket 04
   * decision 1's "all 54 fields" to the 53 that have something to say.
   */
  public static final int NOT_COLLECTED_FIELD = 51;

  public PrintedField {
    Objects.requireNonNull(label, "label");
    values = List.copyOf(Objects.requireNonNull(values, "values"));
    // BL-145. Until the assembler existed, this rule was held by the test fixture alone: nothing
    // stopped a caller reintroducing field 51, and a fixture cannot constrain production code. It
    // lives in the type for the same reason PrintedValue refuses a tagged absence — a rule a later
    // caller can reintroduce is not a rule, it is a convention with a good track record.
    if (number == NOT_COLLECTED_FIELD) {
      throw new IllegalArgumentException(
          "field "
              + NOT_COLLECTED_FIELD
              + " («مستندات الهوية») is NOT on the form (product-owner ruling, 2026-09-14)."
              + " field-provenance.md marks it \"Not collected\": there is no separate upload of a"
              + " national ID or passport, because the Uqudo scan satisfies them, so the row could"
              + " only ever have reported the absence of something nobody asks for.");
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException(
          "field "
              + number
              + " ("
              + label
              + ") must carry EXACTLY ONE value, got "
              + values.size()
              + ". An absent field carries PrintedValue.absent() rather than an empty list (ticket"
              + " 04 decision 7), and no field carries two any more (product-owner ruling,"
              + " 2026-09-14: the registry supplies fields 5/6/7/9/21 and customer entry supplies"
              + " 4/18/23/43/35-41, one source each).");
    }
  }

  /** The field's one value. */
  public PrintedValue value() {
    return values.get(0);
  }

  /**
   * A field: one untagged value.
   *
   * <p><strong>There is no {@code dual(...)} any more, and that is a ruling rather than a
   * simplification.</strong> Ticket 04 decision 2 bound 16 rows to print BOTH values tagged by
   * origin. Read on a real render, that printed «السودان» twice on nationality and the customer's
   * name twice under two tags, and the product owner ruled on 2026-09-14 that each of the 16 takes
   * ONE source: the Civil Registry for fields 5, 6, 7, 9 and 21, and the customer's own entry for
   * fields 4, 18, 23, 43 and 35-41.
   *
   * <p>So no row has a second value, and with nothing to tell apart the origin tag disappears from
   * the body of the form. Provenance is now a property of the FIELD, fixed by field-provenance.md,
   * rather than something printed per row. The «معدَّل» edit marker is unaffected.
   *
   * <p><strong>The ruling is scoped to a profile submitted through the mobile app</strong> (product
   * owner, same day) — the main scenario. A manually completed profile has no registry result at
   * all, so "fields 5/6/7/9/21 come from the registry" has no answer there; that is the assembler's
   * question to put back to the product owner, not one this type can settle.
   */
  public static PrintedField single(int number, String label, PrintedValue value) {
    return new PrintedField(number, label, List.of(value), false, null);
  }

  /**
   * The same field, marked as edited by an operator.
   *
   * <p><strong>Per field, from real data.</strong> Ticket 04 decision 8 wanted provenance stated
   * per field; decision 9 recorded that the capability did not exist — {@code
   * app.profile.provenance} was ONE flag for the whole profile — so every field on a manually
   * completed profile was marked together. S9-02 built {@code app.profile_field_edit} (V0073), one
   * row per field an operator has keyed, and S9-03 pointed the assembler at it. Decision 8's
   * question finally has decision 8's answer.
   *
   * <p>The name it takes is still not printed. The approved form names the operator who PRINTED the
   * sheet, once, in the page footer; it does not attribute individual rows, and an editor's name
   * beside a row would be a second, different claim.
   */
  public PrintedField markedEdited(String operatorName) {
    return new PrintedField(number, label, values, true, operatorName);
  }
}
