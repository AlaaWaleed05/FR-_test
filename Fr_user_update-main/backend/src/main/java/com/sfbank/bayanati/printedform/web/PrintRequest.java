package com.sfbank.bayanati.printedform.web;

/**
 * The operator's ONE remaining print-time answer: whether the attachments ride with the form.
 *
 * <p>This used to carry a {@code variant} field too — {@code unattributed} or {@code attributed} —
 * and the two travelled together because they were one interaction (ticket 05 decision 9, as
 * widened 2026-09-14). AD-022 removed the variant choice: there is one form now, so the question
 * that remains is the attachments one. An unknown field on the wire is ignored, so a stale client
 * still sending {@code variant} is not refused; it simply has no effect.
 *
 * @param includeAttachments defaults to FALSE when the field is absent, which is the decision's
 *     "asked every time, defaulting to no" expressed on the wire. A {@code boolean} rather than a
 *     {@code Boolean} so a missing field can never mean "yes" by accident.
 */
public record PrintRequest(boolean includeAttachments) {}
