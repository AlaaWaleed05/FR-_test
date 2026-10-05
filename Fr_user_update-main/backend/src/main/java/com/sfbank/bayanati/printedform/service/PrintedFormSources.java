package com.sfbank.bayanati.printedform.service;

import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedFormImageSlot;
import com.sfbank.bayanati.printedform.domain.ReferenceLabels;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything {@link PrintedFormAssembler} needs, gathered. One record rather than nine parameters,
 * so the service does the gathering and the assembler stays a function of its inputs.
 *
 * <p>Note what is NOT here: a {@code Clock}, a {@code ReferenceCatalog}, a repository, a list
 * version. Every one of those is a question answered before an instance of this exists — which is
 * what lets the assembler live in a {@code service} package under CLAUDE.md's rule and be exercised
 * by a plain JUnit test with no Spring context, no database, no network and no clock.
 *
 * @param profile what the operator tier already reads, unchanged
 * @param referenceLabels reference labels already pinned to the version the PROFILE used
 * @param images the bytes for each slot that has a committed artifact; a slot missing from the map
 *     prints «غير متاح» in a box of the same height
 * @param salaryCertificate the customer's own pay document, or null when the profile has none or
 *     the operator declined the attachments
 * @param includeAttachments the operator's answer to the one print-time question
 * @param printedBy the operator doing the printing, named in the FOOTER of every page since S9-03 —
 *     «طبع بواسطة الموظف: …», which is AD-022 ruling 2's whole justification for removing the
 *     ATTRIBUTED/UNATTRIBUTED choice. It was the identity band's until then, on page one only, and
 *     true of BOTH variants before AD-022 collapsed them, so no print has ever disclosed less about
 *     who produced it.
 * @param editedFields the form-field NUMBERS an operator has keyed on this profile, driving the
 *     «معدَّل» marker. Resolved DATA, not a port: the assembler must not be able to ask the
 *     database a question, which is what keeps it plain-JUnit-exercisable.
 * @param countryByAlpha3 the Arabic country names for the alpha-3 codes this profile's scan carries
 *     — at most two, fields 4 and 48 — already resolved against the version the profile pinned. A
 *     code the list does not know is simply absent, and the form then prints the raw code rather
 *     than «غير متاح»: the bank holds a real value a branch officer can look up. ICAO issues
 *     document codes that have no ISO row at all ({@code XXA}, {@code GBD}, {@code RKS}, {@code
 *     D}), so this is an ordinary case, not a defect.
 * @param printedAt when, taken from the service's clock
 * @param zone the zone submission and print times are rendered in. A zone, not a clock: given the
 *     same two instants this record always produces the same page.
 */
public record PrintedFormSources(
    ProfileDetail profile,
    ReferenceLabels referenceLabels,
    Map<PrintedFormImageSlot, byte[]> images,
    PrintedFormDocument.SalaryCertificate salaryCertificate,
    boolean includeAttachments,
    String printedBy,
    Set<Integer> editedFields,
    Map<String, String> countryByAlpha3,
    Instant printedAt,
    ZoneId zone) {

  public PrintedFormSources {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(referenceLabels, "referenceLabels");
    Objects.requireNonNull(printedBy, "printedBy");
    Objects.requireNonNull(printedAt, "printedAt");
    Objects.requireNonNull(zone, "zone");
    // EnumMap rather than Map.copyOf: the values are image bytes and a null value for a slot is
    // not a legal way to say "absent" (leave the key out), but Map.copyOf would also reject the
    // empty map's identity, and an EnumMap keeps iteration in slot order for free.
    Map<PrintedFormImageSlot, byte[]> copy = new EnumMap<>(PrintedFormImageSlot.class);
    copy.putAll(Objects.requireNonNull(images, "images"));
    images = copy;
    editedFields = Set.copyOf(Objects.requireNonNull(editedFields, "editedFields"));
    countryByAlpha3 = Map.copyOf(Objects.requireNonNull(countryByAlpha3, "countryByAlpha3"));
  }
}
