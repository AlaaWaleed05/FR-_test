package com.sfbank.bayanati.printedform.service;

import static com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary.LIST_ADMIN_DIVISION;

import static com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary.LIST_COUNTRY;
import static com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary.LIST_EDUCATION_LEVEL;
import static com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary.LIST_INCOME_SOURCE;
import static com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary.LIST_OCCUPATION;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.operator.domain.ChannelStateView;
import com.sfbank.bayanati.operator.domain.CustomerDataView;
import com.sfbank.bayanati.operator.domain.IncomeSourceView;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.RegistryResultView;
import com.sfbank.bayanati.operator.domain.ScanResultView;
import com.sfbank.bayanati.printedform.domain.FieldOrigin;
import com.sfbank.bayanati.printedform.domain.PrintedChip;
import com.sfbank.bayanati.printedform.domain.PrintedField;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedFormImageSlot;
import com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary;
import com.sfbank.bayanati.printedform.domain.PrintedImage;
import com.sfbank.bayanati.printedform.domain.PrintedSection;
import com.sfbank.bayanati.printedform.domain.PrintedValue;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a real profile into the document the renderer lays out. This is the half of BL-132 that had
 * never been written: the renderer, its document model and the FOP wiring all shipped at S8-33 and
 * S8-35 with nothing calling them.
 *
 * <p><strong>The section order is the APPROVED DESIGN's</strong> — three sections, the third
 * carrying six sub-headings on its own page. This paragraph used to say the opposite: that the form
 * followed {@code field-provenance.md}'s nine sections, that ticket 04 decision 1 bound it to them,
 * and that where an artboard departed the decision governed. AD-021 restructured screen and form
 * into three sections, AD-022 made the approved artboards the build target, and the product owner
 * settled the question directly on 2026-09-16: the approved design over-rules the bank paper form's
 * running order.
 *
 * <p>Fields are BUILT by source and PLACED by layout — see {@code Fields.sections()} for why those
 * are separate, and for the check that stops a field being built and never placed.
 *
 * <p><strong>One source per field.</strong> Product-owner ruling, 2026-09-14, and it is settled: no
 * field prints two values and {@link PrintedField} refuses a second. The Civil Registry supplies
 * fields 5, 6, 7, 9 and 21; customer entry supplies 4, 18, 23, 43 and 35-41. Where a value is
 * genuinely absent the field prints «غير متاح» and carries no origin at all, which {@link
 * PrintedValue} enforces — an absence attributed to a source claims the value is both unavailable
 * and read from somewhere.
 *
 * <p><strong>The no-registry case is not handled here and must not be.</strong> A profile cannot
 * reach {@code submitted} without a Civil Registry record, and where there is no registry result
 * there is no Uqudo document either, so the fallback an earlier draft wanted had nothing to fall
 * back to (product owner, 2026-09-14, closing the question rather than answering it). A manually
 * completed profile does reach {@code submitted} with no registry result at all; its five registry
 * fields print «غير متاح», which is decision 7's rule working exactly as intended — an operator
 * cannot assert an identity, and the form must show that as an absence rather than as a blank.
 *
 * <p><strong>Field 4 is worth knowing about before reading the code.</strong> The display ruling
 * lists it under "customer entry only", but there is no customer-entered nationality anywhere in
 * the schema — {@code app.profile_customer_data} has no such column and V0023 says so in terms
 * ("nationality (field 4) is Uqudo-sourced (S2) and already lives on app.scan_result.nationality").
 * What the ruling achieved for field 4 was therefore "one value, not two", which is what this
 * assembler produces. Recorded rather than guessed at.
 */
public final class PrintedFormAssembler {

  /**
   * D4.2's date shape, the one every customer- and operator-facing surface in this system uses:
   * {@code DD/MM/YYYY}, 24-hour, Latin digits, {@code /} separators, never ISO. {@code Locale.ROOT}
   * is not decoration — an Arabic locale would render these in Arabic-Indic digits.
   */
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);

  /**
   * «اسم الزوج» and «اسم الزوجة» — two form NUMBERS over one {@code spouse_name} column, exactly
   * one of which is printed. The resolved sex chooses; see {@code Fields.socialStatus} and AD-022
   * (m). Both were printed until 2026-09-18, which is BL-164. An edit is recorded against 13
   * whichever row renders it; see {@code Fields.isEdited}.
   */
  private static final int SPOUSE_NAME_HUSBAND_ROW = 13;

  private static final int SPOUSE_NAME_WIFE_ROW = 14;

  /**
   * FOUR rows may be absent from a form, and the field set therefore varies per profile: 25 and 26,
   * because a channel the customer did not verify is not printed at all (AD-022 ruling 4); and
   * whichever of 13 and 14 the resolved sex did not select (AD-022 (m)). It was two until
   * 2026-09-18 — {@code Fields.place} carries the same count and the two must not drift apart.
   */
  private static final int CONTACT_PHONE_ROW = 25;

  private static final int CONTACT_EMAIL_ROW = 26;

  private PrintedFormAssembler() {}

  public static PrintedFormDocument assemble(PrintedFormSources sources) {
    ProfileDetail profile = sources.profile();
    Fields fields = new Fields(sources);

    return new PrintedFormDocument(
        fields.customerName(),
        profile.referenceNumber(),
        formatDateTime(profile.submittedAt(), sources.zone()),
        sources.printedBy(),
        formatDateTime(sources.printedAt(), sources.zone()),
        DATE.format(sources.printedAt().atZone(sources.zone())),
        verificationChips(profile),
        fields.sections(),
        images(sources),
        sources.includeAttachments(),
        sources.includeAttachments() ? sources.salaryCertificate() : null);
  }

  /**
   * The two chips the approved page 1 sets above the tiles, and the only two AD-022 ruling 3
   * permits on paper.
   *
   * <h2>Neither is read from a score, and the liveness one could not be</h2>
   *
   * <p>There is no liveness outcome column anywhere. V0008 says so in as many words — "there is
   * deliberately NO liveness score column. Uqudo returns none; a liveness failure produces no JWS
   * at all and is recorded in audit as a terminated attempt (AD-002a)". <strong>So the presence of
   * a face result IS the liveness pass</strong>: a customer who failed liveness never produced the
   * JWS that writes the row.
   *
   * <p>{@code FaceResultView.passed} is the trap. It reads like the answer and is a GENERATED
   * column, {@code match AND match_level >= threshold_applied} — the FACE MATCH, whose display
   * AD-022 ruling 3 removed outright. Printing it would breach the ruling while looking like it
   * honoured it. Neither {@code match}, {@code matchLevel}, {@code thresholdApplied} nor {@code
   * passed} is read here, and none may be.
   *
   * <h2>Two states, not the screen's three</h2>
   *
   * <p>{@code ProfileDetailPage.tsx} draws a "فشل" chip on {@code blocked_liveness}. That branch is
   * unreachable here: {@code PrintedFormService.PRINTABLE_STATUSES} is {@code submitted} and {@code
   * approved} only, so a blocked profile cannot be printed at all. Writing the branch anyway would
   * be a claim no print could ever make.
   *
   * <p><strong>«لم يتم بعد» is not dead, though, and that is the case worth guarding.</strong> The
   * artboards draw «ناجح» flat, and this type permits a null face result on a printable profile —
   * so rendering the artboard's wording unconditionally would assert a liveness pass on a customer
   * the record does not say reached stage 10. The branch guards the MODEL's shape, not any
   * particular population: AD-022 ruling (g) flushes the pre-ruling manually completed profiles
   * that are today's example of it, and the branch would still be right afterwards, because nothing
   * in the type stops a future caller handing this method a profile without one.
   */
  private static List<PrintedChip> verificationChips(ProfileDetail profile) {
    PrintedChip liveness =
        profile.faceResult() != null
            ? PrintedChip.good("التحقق الحي — ناجح")
            : PrintedChip.neutral("التحقق الحي — لم يتم بعد");

    ScanResultView scan = profile.scanResult();
    Boolean mrz = scan == null ? null : scan.mrzVerified();
    PrintedChip mrzChip;
    if (mrz == null) {
      mrzChip = PrintedChip.neutral("تحقق MRZ — لم يتم بعد");
    } else if (mrz) {
      mrzChip = PrintedChip.neutral("تحقق MRZ — صحيح");
    } else {
      mrzChip = PrintedChip.bad("تحقق MRZ — غير صحيح");
    }

    return List.of(liveness, mrzChip);
  }

  /**
   * The five images, in layout order — FOUR of which get a tile on page 1. A slot with no committed
   * artifact becomes an ABSENT image rather than being dropped: the box still prints, at the same
   * height as its row-mates, carrying «غير متاح». Dropping it would reflow the grid and silently
   * hide that the bank holds no such image.
   *
   * <p><strong>The liveness frame is in the list and off the grid</strong> (AD-022, product-owner
   * ruling 2026-09-16). The approved page 1 shows four tiles and the frame is not among them, but
   * it is still captured, still stored and still printed as its own appended sheet when an operator
   * asks for attachments. It stays in this list for exactly that reason: {@code
   * FoDocumentWriter.attachments} walks the same list, so removing it here would have taken the
   * attachment with it — silently, with nothing failing.
   */
  private static List<PrintedImage> images(PrintedFormSources sources) {
    FieldOrigin documentOrigin = documentOrigin(sources.profile());
    List<PrintedImage> images = new ArrayList<>();
    for (PrintedFormImageSlot slot : PrintedFormImageSlot.inFormOrder()) {
      byte[] bytes = sources.images().get(slot);
      FieldOrigin origin =
          switch (slot) {
            case DOCUMENT, DOCUMENT_PORTRAIT, LIVENESS -> documentOrigin;
            case REGISTRY_PORTRAIT -> FieldOrigin.CIVIL_REGISTRY;
            case SIGNATURE -> FieldOrigin.CUSTOMER;
          };
      PrintedImage image =
          bytes == null
              ? PrintedImage.absent(slot.caption(), origin)
              : new PrintedImage(slot.caption(), origin, bytes, true);
      images.add(slot == PrintedFormImageSlot.LIVENESS ? image.attachmentOnly() : image);
    }
    return images;
  }

  /**
   * Which DOCUMENT the scan's values were read from — «جواز سفر», «بطاقة قومية», or «وثيقة الهوية»
   * when the type is not recorded. Never the vendor: Uqudo is a tool, not a source (product-owner
   * ruling, 2026-09-14). A branch officer needs to know which document to ask the customer for
   * again, and naming the scanning vendor tells them nothing.
   */
  private static FieldOrigin documentOrigin(ProfileDetail profile) {
    ScanResultView scan = profile.scanResult();
    return FieldOrigin.ofScannedDocument(scan == null ? null : scan.documentType());
  }

  private static String formatDateTime(Instant instant, ZoneId zone) {
    return instant == null ? null : DATE_TIME.format(instant.atZone(zone));
  }

  /** Gathers the three sections. An inner class purely so the many helpers share the sources. */
  private static final class Fields {

    private final PrintedFormSources sources;
    private final ProfileDetail profile;
    private final CustomerDataView customer;
    private final ScanResultView scan;
    private final RegistryResultView registry;
    private final FieldOrigin documentOrigin;

    /**
     * The form-field numbers an operator has keyed on this profile — {@code app.profile_field_edit}
     * (V0073), resolved before the assembler ever sees it.
     *
     * <p><strong>This replaces a profile-level predicate, and the change is the point of the
     * marker.</strong> Ticket 04 decision 8 asked for provenance PER FIELD; decision 9 recorded
     * that the capability did not exist, so the marker degenerated to one flag for the whole
     * profile and marked every customer-entered field on a manually completed one together. S9-02
     * built the per-field storage and S9-03 reads it, so decision 8's question finally has decision
     * 8's answer.
     *
     * <p><strong>What a legacy manually completed profile now prints, stated rather than
     * discovered.</strong> Such a profile has no rows here — nobody EDITED it, an operator keyed it
     * at completion — so its fields carry no marker at all, where they used to carry «يدوي». That
     * is a disclosure the approved form does not have a word for: AD-022 deleted manual completion,
     * and «معدَّل» would be a false claim on that population, asserting an operator changed a value
     * the customer submitted when the customer submitted nothing. Printing nothing is an omission;
     * printing «معدَّل» would be a untruth, and the form is a filed bank document.
     */
    private final Set<Integer> editedFields;

    Fields(PrintedFormSources sources) {
      this.sources = sources;
      this.profile = sources.profile();
      this.customer = profile.customerData();
      this.scan = profile.scanResult();
      this.registry = profile.registryResult();
      this.documentOrigin = documentOrigin(profile);
      this.editedFields = sources.editedFields();
    }

    /**
     * The name on the compact identity line under the header: the Civil Registry's four-part Arabic
     * chain, which is field 5. Falls back to the scanned document's own Arabic name and then to the
     * reference number, so the line is never blank on a profile the registry could not name — a
     * manually completed one.
     */
    String customerName() {
      String registryName = arabicNameChain();
      if (registryName != null) {
        return registryName;
      }
      if (scan != null && notBlank(scan.nameArOnDocument())) {
        return scan.nameArOnDocument();
      }
      return profile.referenceNumber();
    }

    /**
     * The approved design's THREE sections, in the artboards' own order.
     *
     * <p>Nine until S9-03. The form followed the bank's paper form, and this type's predecessor
     * said in terms that the artboards "must not be copied" for their order. AD-021 restructured
     * screen and form into three sections, AD-022 made the approved artboards the build target, and
     * on 2026-09-16 the product owner answered the question directly: the approved design
     * over-rules the paper form's running order.
     *
     * <p><strong>The fields are BUILT by source and PLACED by layout, and the two are separate on
     * purpose.</strong> Every field expression stays in the group that owns its data — the registry
     * fields together, the address fields together — and this method states only where each number
     * appears on the page. Regrouping the expressions themselves would have meant moving fifty
     * field definitions to express a layout change, and a definition that moves is a definition
     * that can be altered in the moving.
     *
     * <p>The leftover check at the end is what makes that safe: a field built and not placed is a
     * field silently dropped from a filing document, which is the failure this whole form exists to
     * avoid. It throws rather than printing a short form.
     */
    List<PrintedSection> sections() {
      Map<Integer, PrintedField> built = new LinkedHashMap<>();
      for (PrintedField field : buildAll()) {
        built.put(field.number(), field);
      }

      // EXACTLY ONE SPOUSE ROW, always. AD-022 (m) made 13 and 14 mutually exclusive, which forced
      // place() to tolerate each of them being absent; this is what keeps that tolerance from
      // hiding a form that carries NEITHER. Checked here rather than inside socialStatus() because
      // this is the level that owns "the form is complete" -- the same job as the leftover check
      // below, and the two together say every field built is placed and every row required exists.
      boolean husbandRow = built.containsKey(SPOUSE_NAME_HUSBAND_ROW);
      boolean wifeRow = built.containsKey(SPOUSE_NAME_WIFE_ROW);
      if (husbandRow == wifeRow) {
        throw new IllegalStateException(
            "exactly one spouse row must be built, got "
                + (husbandRow ? "both 13 and 14" : "neither 13 nor 14"));
      }

      List<PrintedSection> sections = new ArrayList<>();
      // COLUMN-MAJOR, flattened. The artboard sets 5/6/7 down one column and 8/9/21 down the
      // other; FoDocumentWriter pairs ADJACENT fields two to a row. So the order that renders the
      // artboard's grid is 5,8 then 6,9 then 7,21 -- not ascending. Same for section 2.
      sections.add(
          PrintedSection.badged(
              "١ — بيانات الهوية", "من السجل المدني", place(built, 5, 8, 6, 9, 7, 21)));
      sections.add(
          PrintedSection.badged(
              "٢ — التحقق من الهوية",
              "من وثيقة الهوية والتحقق الحي",
              place(built, 43, 46, 44, 47, 45, 48)));

      // Section 3 is the whole of page 2: its own heading carries no fields, and the six
      // sub-headings beneath it carry them all in one column.
      sections.add(
          PrintedSection.fullWidth("٣ — البيانات المُقدَّمة من العميل", List.of())
              .startingNewPage());
      sections.add(PrintedSection.subHeading("الحساب", place(built, 2)));
      sections.add(PrintedSection.subHeading("قنوات الاتصال", place(built, 25, 26)));
      sections.add(
          PrintedSection.subHeading(
              "البيانات الشخصية والاجتماعية",
              // 24 before 23 is the artboard's own order: state, then city.
              place(built, 4, 10, 11, 12, 13, 14, 15, 16, 17, 22, 24, 23)));
      sections.add(PrintedSection.subHeading("المهنة والدخل", place(built, 18, 19, 20)));
      sections.add(
          PrintedSection.subHeading("عنوان السكن", place(built, 35, 36, 37, 38, 39, 40, 41, 42)));
      sections.add(
          PrintedSection.subHeading(
              "جهة العمل عنوانه", place(built, 27, 28, 29, 30, 31, 32, 33, 34)));

      if (!built.isEmpty()) {
        throw new IllegalStateException(
            "every field built must be placed on the form; these were not: " + built.keySet());
      }
      return sections;
    }

    /** Every field this profile has, by source. Placement is {@link #sections()}'s business. */
    private List<PrintedField> buildAll() {
      List<PrintedField> all = new ArrayList<>();
      all.addAll(account());
      all.addAll(personalData());
      all.addAll(socialStatus());
      all.addAll(birthData());
      all.addAll(contact());
      all.addAll(workAddress());
      all.addAll(homeAddress());
      all.addAll(identityDocument());
      return all;
    }

    /**
     * The named fields, in the order given, REMOVED from the pool as they are placed.
     *
     * <p>A number with no field is skipped rather than refused: {@link #contact()} returns only the
     * channels this customer verified (AD-022 ruling 4), so 25 or 26 legitimately may not exist.
     * Removing as we go is what lets the caller prove nothing was left behind.
     */
    private List<PrintedField> place(Map<Integer, PrintedField> pool, int... numbers) {
      List<PrintedField> placed = new ArrayList<>(numbers.length);
      for (int number : numbers) {
        PrintedField field = pool.remove(number);
        if (field != null) {
          placed.add(field);
          continue;
        }
        // Absent is legal for exactly four numbers. Anywhere else it means the layout names a field
        // no builder produces -- a row that would simply never appear, which is the same silent
        // omission the leftover check exists to prevent, arriving from the other direction.
        //
        // 25 and 26 are absent when the customer verified no such channel (AD-022 ruling 4). 13 and
        // 14 joined them at AD-022 (m): the layout names both spouse numbers because either may be
        // the one printed, and exactly one of them always is. That "exactly one" is NOT weakened
        // into "either or neither" by this exemption -- sections() asserts it directly, before
        // placement, precisely because this relaxation would otherwise let a form with NO spouse
        // row
        // through silently.
        if (number != CONTACT_PHONE_ROW
            && number != CONTACT_EMAIL_ROW
            && number != SPOUSE_NAME_HUSBAND_ROW
            && number != SPOUSE_NAME_WIFE_ROW) {
          throw new IllegalStateException(
              "the form's layout names field " + number + ", which nothing builds");
        }
      }
      return placed;
    }

    // ---- Account and branch (2-3) ------------------------------------------------------

    /**
     * Fields 3 and 2, in the artboard's order — the account number first.
     *
     * <p><strong>Field 1 «التاريخ» is no longer a row.</strong> It is the submission date, and the
     * approved design prints it as header chrome on every page rather than as a numbered field in
     * the body. It leaves the field set here; the header renders it from {@code
     * PrintedFormDocument} directly.
     */
    private List<PrintedField> account() {
      return List.of(
          // «الفرع» and «رقم الحساب البنكي», not «المصرف» and «رقم العميل» -- product-owner
          // corrections, 2026-09-14. Both are the vocabulary the rest of the system already uses.
          
          customerField(2, "رقم الحساب البنكي", latin(profile.accountNumber())));
    }

    // ---- Personal data (4-11) ----------------------------------------------------------

    private List<PrintedField> personalData() {
      return List.of(
          // Field 4: the MRZ's three-letter nationality, resolved to the country's Arabic name.
          //
          // CORRECTED 2026-09-16 (S9-03). This comment used to say the opposite -- that a lookup
          // could not work, because the list's item codes are alpha-2 ('SD') and the MRZ carries
          // alpha-3 ('SDN'), and that "inventing a mapping would put a label on the form that the
          // document the officer holds does not carry". The premise was wrong and BL-157 was closed
          // by reversing it: V0022 seeds {"alpha3":"SDN"} into every country row's `extra`, so the
          // mapping is SERVER-SUPPLIED and version-pinned, not invented. Nothing was ever wrong
          // with
          // the reasoning -- only with the fact it rested on, which nobody had checked.
          //
          // An unresolved code still prints as received. ICAO issues document codes with no ISO row
          // at all (XXA, GBD, RKS, D), and the raw code is a real value a branch officer can look
          // up.
          //
          // scanField, NOT customerField, although the display ruling lists field 4 under "customer
          // entry only". There is no customer-entered nationality column anywhere in the schema, so
          // the ruling is unsatisfiable as literally written and what it achieved here was "one
          // value, not two". Attributing the MRZ's value to the customer's own data entry would be
          // false inside the model; nothing visible changes either way, because the renderer prints
          // no origin tag for any field. Flagged for the product owner rather than settled here.
          scanField(4, "الجنسية", country(scan == null ? null : scan.nationality())),
          registryField(5, "الاسم بالعربي", arabic(arabicNameChain())),
          registryField(6, "الاسم بالانجليزي", latin(englishName())),
          registryField(
              7,
              "الرقم الوطني",
              latin(registry == null ? null : registry.identityNumberReturned())),
          registryField(8, "اسم الأم", arabic(maternalNameChain())),
          // Field 9 is the bank's label for SEX and field 10 for ETHNICITY. The form prints the
          // bank's labels, not the literal reading -- field-provenance.md's closing note.
          registryField(
              9,
              "النوع",
              arabic(PrintedFormVocabulary.sex(registry == null ? null : registry.sexRegistry()))),
          customerField(10, "الجنس", arabic(customer == null ? null : customer.ethnicity())),
          customerField(
              11,
              "المواطنة",
              arabic(
                  reference(
                      LIST_COUNTRY, customer == null ? null : customer.countryOfResidenceCode()))));
    }

    // ---- Social status (12-20) ---------------------------------------------------------

    private List<PrintedField> socialStatus() {
      String spouse = customer == null ? null : customer.spouseName();
      // ONE spouse_name column, ONE printed row. The bank's schema has two numbers for it -- 13
      // «اسم الزوج» and 14 «اسم الزوجة» -- and which one is printed follows the customer's own sex.
      // The registry's sex governs, because field 9 does; the declared value is the fallback on a
      // profile the registry never answered for.
      //
      // BOTH rows were emitted until AD-022 (m), 2026-09-18, so one of them always read «غير متاح»
      // and a married woman's form carried «اسم الزوجة: غير متاح» beneath her husband's name
      // (BL-164, found live on a staging profile). That is ruling (f)'s reasoning applied where (f)
      // did not reach: a bank form telling a woman her wife's name is unavailable is a visible
      // defect on a document she may be handed. The approved artboard draws field 13 alone, and the
      // back office has rendered a single sex-selected row since BL-161.
      //
      // The unmarried case is deliberately unchanged: she still gets her one row, reading
      // «غير متاح». The ruling is about WHICH row, not about whether an empty row prints.
      boolean female = "f".equalsIgnoreCase(effectiveSex());
      return List.of(
          customerField(
              12,
              "الحالة الاجتماعية",
              arabic(
                  PrintedFormVocabulary.maritalStatus(
                      customer == null ? null : customer.maritalStatus(), female))),
          customerField(
              female ? SPOUSE_NAME_HUSBAND_ROW : SPOUSE_NAME_WIFE_ROW,
              female ? "اسم الزوج" : "اسم الزوجة",
              arabic(spouse)),
          customerField(
              15,
              "له أطفال",
              arabic(
                  PrintedFormVocabulary.yesNo(customer == null ? null : customer.hasChildren()))),
          customerField(
              16,
              "عدد الأطفال",
              latin(
                  customer == null || customer.childrenCount() == null
                      ? null
                      : String.valueOf(customer.childrenCount()))),
          customerField(
              17,
              "مستوي التعليم",
              arabic(
                  reference(
                      LIST_EDUCATION_LEVEL,
                      customer == null || customer.educationLevel() == null
                          ? null
                          : String.valueOf(customer.educationLevel())))),
          customerField(
              18,
              "المهنة",
              arabic(
                  reference(LIST_OCCUPATION, customer == null ? null : customer.occupationCode()))),
          customerField(19, "النفقات الشهرية", latin(monthlyExpenses())),
          customerField(20, "مصدر الدخل", arabic(incomeSources())));
    }

    // ---- Birth data (21-24) ------------------------------------------------------------

    private List<PrintedField> birthData() {
      return List.of(
          registryField(
              21,
              "تاريخ الميلاد",
              latin(formatDate(registry == null ? null : registry.dateOfBirth()))),
          customerField(
              22,
              "بلد الميلاد",
              arabic(
                  reference(LIST_COUNTRY, customer == null ? null : customer.birthCountryCode()))),
          customerField(
              23, "مدينة الميلاد", arabic(customer == null ? null : customer.birthCityText())),
          customerField(
              24,
              "ولاية الميلاد",
              arabic(
                  codeOrText(
                      LIST_ADMIN_DIVISION,
                      customer == null ? null : customer.birthStateCode(),
                      customer == null ? null : customer.birthStateText()))));
    }

    // ---- Contact (25-26) ---------------------------------------------------------------

    /**
     * Only the channels this customer VERIFIED (AD-022 ruling 4).
     *
     * <p>A declined or unverified channel is not shown at all — not greyed, not tagged, and not
     * «غير متاح» either. The row simply does not exist, which is the product owner's ruling as
     * given: a form that printed an unverified number would put a contact detail the bank has not
     * proved in front of a branch officer who would then use it.
     *
     * <p><strong>The phone backs TWO channels.</strong> A customer may verify SMS, WhatsApp or
     * both, and both are the same number, so field 25 prints when EITHER is verified. Field 26 has
     * one channel. This mirrors {@code ProfileDetailPage.tsx} exactly, including that the test is
     * on {@code state} and never on {@code verifiedAt}: a row can carry a timestamp from an earlier
     * cycle while its current state is not verified.
     *
     * <p>This is the one place the form's field set varies per profile, which is why {@link
     * #sections()} places by number and tolerates an absent one.
     */
    private List<PrintedField> contact() {
      Set<MessageChannel> verified = verifiedChannels();
      List<PrintedField> fields = new ArrayList<>(2);
      if (verified.contains(MessageChannel.SMS) || verified.contains(MessageChannel.WHATSAPP)) {
        fields.add(
            customerField(25, "التلفون", latin(customer == null ? null : customer.phoneNumber())));
      }
      if (verified.contains(MessageChannel.EMAIL)) {
        fields.add(
            customerField(
                26, "البريد الالكتروني", latin(customer == null ? null : customer.emailAddress())));
      }
      return fields;
    }

    private Set<MessageChannel> verifiedChannels() {
      EnumSet<MessageChannel> verified = EnumSet.noneOf(MessageChannel.class);
      for (ChannelStateView channel : profile.channels()) {
        if (channel.state() == ChannelState.VERIFIED) {
          verified.add(channel.channel());
        }
      }
      return verified;
    }

    // ---- Work address (27-34) ----------------------------------------------------------

    private List<PrintedField> workAddress() {
      return List.of(
          customerField(27, "جهة العمل", arabic(customer == null ? null : customer.employerName())),
          customerField(
              28,
              "البلد",
              arabic(
                  reference(LIST_COUNTRY, customer == null ? null : customer.workCountryCode()))),
          customerField(
              29,
              "الولاية",
              arabic(
                  codeOrText(
                      LIST_ADMIN_DIVISION,
                      customer == null ? null : customer.workStateCode(),
                      customer == null ? null : customer.workStateText()))),
          customerField(
              30,
              "المحافظة",
              arabic(
                  codeOrText(
                      LIST_ADMIN_DIVISION,
                      customer == null ? null : customer.workLocalityCode(),
                      customer == null ? null : customer.workLocalityText()))),
          customerField(31, "المنطقة", arabic(customer == null ? null : customer.workArea())),
          customerField(32, "المدينة", arabic(customer == null ? null : customer.workCity())),
          customerField(33, "الشارع", arabic(customer == null ? null : customer.workStreet())),
          customerField(34, "المربع", block(customer == null ? null : customer.workBlock())));
    }

    // ---- Home address (35-42) ----------------------------------------------------------

    private List<PrintedField> homeAddress() {
      // ALL CUSTOMER ENTRY, and the Civil Registry's address string is not on the form at all.
      // Product-owner ruling, 2026-09-14, confirmed when the consequence was put to them: an
      // earlier round kept the registry's one undecomposed string on field 35 so it appeared
      // somewhere. The registry's address is where it believes the customer lived; refreshing it
      // is the entire purpose of the campaign, so the form shows what the customer says now.
      return List.of(
          customerField(
              35,
              "البلد",
              arabic(
                  reference(LIST_COUNTRY, customer == null ? null : customer.homeCountryCode()))),
          customerField(
              36,
              "الولاية",
              arabic(
                  codeOrText(
                      LIST_ADMIN_DIVISION,
                      customer == null ? null : customer.homeStateCode(),
                      customer == null ? null : customer.homeStateText()))),
          customerField(
              37,
              "المحافظة",
              arabic(
                  codeOrText(
                      LIST_ADMIN_DIVISION,
                      customer == null ? null : customer.homeLocalityCode(),
                      customer == null ? null : customer.homeLocalityText()))),
          customerField(38, "المنطقة", arabic(customer == null ? null : customer.homeArea())),
          customerField(39, "المدينة", arabic(customer == null ? null : customer.homeCity())),
          customerField(40, "الشارع", arabic(customer == null ? null : customer.homeStreet())),
          customerField(41, "المربع", block(customer == null ? null : customer.homeBlock())),
          customerField(42, "رقم المنزل", latin(customer == null ? null : customer.homeHouseNo())));
    }

    // ---- Identity document (43-48) -----------------------------------------------------

    private List<PrintedField> identityDocument() {
      // Field 43 prints the CUSTOMER'S choice (display ruling, 2026-09-14). field-provenance.md
      // notes "S3 chooses, S2 confirms. A mismatch is an operator signal", and printing the
      // customer's choice alone removes the only place that signal was visible on a filed form.
      // That consequence is recorded on ticket 04 as still open; it is not this build's to settle.
      return List.of(
          customerField(
              43,
              "نوع الهوية",
              arabic(
                  PrintedFormVocabulary.identityType(
                      customer == null ? null : customer.identityType()))),
          scanField(44, "رقم الهوية", latin(scan == null ? null : scan.documentNumber())),
          scanField(
              45, "تاريخ الإصدار", latin(formatDate(scan == null ? null : scan.dateOfIssue()))),
          scanField(46, "مكان الإصدار", arabic(scan == null ? null : scan.placeOfIssue())),
          scanField(
              47, "تاريخ الصلاحية", latin(formatDate(scan == null ? null : scan.dateOfExpiry()))),
          // Field 48, same alpha-3 resolution as field 4 and for the same reason.
          scanField(48, "بلد الإصدار", country(scan == null ? null : scan.issuingCountry())));
    }

    // ---- Fields 49 and 50 are NOT rows ------------------------------------------------
    //
    // «التوقيع» (49) survives on the approved page 1 as an image TILE with its own caption, not as
    // a row saying «مرفق»; and «شهادة مرتب» (50) appears on neither page. The certificate itself
    // still rides in the same PDF when the operator asks for attachments -- what went is the row
    // that announced it. docs/backoffice-redesign.md section 4: "No declaration block and no
    // signature lines -- the customer signs in the app, and the signature is on page 1."

    // ---- Value plumbing ---------------------------------------------------------------

    /** A field whose value came from the customer's own data entry. */
    private PrintedField customerField(int number, String label, Value value) {
      return mark(field(number, label, value.withOrigin(FieldOrigin.CUSTOMER)));
    }

    /** A field the Civil Registry supplies. Never marked manual: no operator can key one. */
    private PrintedField registryField(int number, String label, Value value) {
      return field(number, label, value.withOrigin(FieldOrigin.CIVIL_REGISTRY));
    }

    /** A field read off the scanned document, tagged with the DOCUMENT rather than the vendor. */
    private PrintedField scanField(int number, String label, Value value) {
      return field(number, label, value.withOrigin(documentOrigin));
    }

    private PrintedField field(int number, String label, Value value) {
      return PrintedField.single(number, label, value.toPrintedValue());
    }

    private PrintedField mark(PrintedField field) {
      if (!isEdited(field.number())) {
        return field;
      }
      // AN ABSENT VALUE IS NEVER MARKED. Found at review, and it is the same claim ticket 04's
      // round-two correction 3 exists to forbid, arriving by a different door: PrintedValue refuses
      // to construct a tagged absence, but the marker sits on the FIELD rather than on the value,
      // so it walked straight around that guard. An operator cannot have keyed a value that is not
      // there -- and this is reachable now in a way it was not before, because a real edit row can
      // exist against a field the customer later left empty.
      if (field.value().isAbsent()) {
        return field;
      }
      // The name is withheld. The approved form names the operator who PRINTED the sheet, once, in
      // the page footer; a second name beside a row would be a different claim wearing the same
      // clothes. PrintedField.editedBy is retained for the answer, and nothing renders it.
      return field.markedEdited(null);
    }

    /**
     * Whether this form row was edited.
     *
     * <p><strong>Rows 13 and 14 share one column, and the edit is recorded against 13.</strong>
     * {@code app.profile_customer_data.spouse_name} backs both «اسم الزوج» (13) and «اسم الزوجة»
     * (14), and the row that is printed follows the customer's own sex: 13 for a married woman,
     * naming her husband, 14 for a married man, naming his wife. {@code EditableField.SPOUSE_NAME}
     * records field number 13 whichever row renders.
     *
     * <p>So a marker keyed on the stored number alone marks a married WOMAN's row and silently
     * misses a married MAN's — the same column, the same edit, a marker or no marker depending only
     * on the customer's sex. Treating an edit of 13 as an edit of 14 marks whichever row was
     * printed.
     *
     * <p><strong>This expansion did not become redundant when AD-022 (m) collapsed the two rows
     * into one — it became the ONLY thing standing between a married man and an unmarked
     * operator-keyed value.</strong> Before (m) a wrong answer here printed a missing marker beside
     * a row that was rendered either way; now field 14 is the entire spouse row on a male profile,
     * so dropping the expansion would silently unmark it. Deleting this second clause looks like
     * tidying a special case for a row that no longer exists beside its twin. It is not.
     */
    private boolean isEdited(int number) {
      return editedFields.contains(number)
          || (number == SPOUSE_NAME_WIFE_ROW && editedFields.contains(SPOUSE_NAME_HUSBAND_ROW));
    }

    // ---- Reference and text helpers ---------------------------------------------------

    /**
     * A reference-coded value's Arabic label, pinned to the version the profile used. An
     * unresolvable code falls back to the code itself rather than to «غير متاح» — the bank holds a
     * real value and a branch officer can look a number up, while "not available" would be a
     * statement about the customer that is simply false.
     */
    /**
     * A country the SCAN named, by its MRZ alpha-3 code.
     *
     * <p>Resolved gives the Arabic name and an ARABIC-script value; unresolved gives the raw code
     * back as a LATIN-script one, so the bidi wrapper still matches what is actually printed. The
     * resolution itself happened in {@code PrintedFormService} -- the assembler may not reach a
     * database, which is what keeps it exercisable by a plain JUnit test.
     */
    private Value country(String alpha3) {
      if (!notBlank(alpha3)) {
        return latin(null);
      }
      String name = sources.countryByAlpha3().get(alpha3);
      return name != null ? arabic(name) : latin(alpha3);
    }

    /**
     * «المربع» — a block number, which the approved page 2 sets in Latin digits.
     *
     * <p>Conditional, because the column is not: {@code work_block} and {@code home_block} are free
     * text with no numeric keyboard and no formatter, so a customer may type «مربع ١٢» or «12 شرق».
     * Declaring such a value Latin would wrap it in an LTR embedding and reorder its runs — the
     * mixed-script defect {@link PrintedValue} warns about. Digits render Latin, as the artboard
     * shows; anything else stays Arabic and reads correctly.
     */
    private Value block(String value) {
      return notBlank(value) && value.chars().allMatch(Character::isDigit)
          ? latin(value)
          : arabic(value);
    }

    private String reference(String listCode, String itemCode) {
      if (!notBlank(itemCode)) {
        return null;
      }
      return sources.referenceLabels().label(listCode, itemCode).orElse(itemCode);
    }

    /** A cascading address level: the coded value where one exists, else the free-text fallback. */
    private String codeOrText(String listCode, String itemCode, String freeText) {
      String label = reference(listCode, itemCode);
      return label != null ? label : freeText;
    }

    private String arabicNameChain() {
      return registry == null
          ? null
          : join(
              registry.nameArGiven(),
              registry.nameArFather(),
              registry.nameArGrandfather(),
              registry.nameArGreatGrandfather());
    }

    /**
     * The maternal chain is FOUR fields, not one — mother, her father, her grandfather, her
     * great-grandfather — matching the paternal chain. field-provenance.md field 8, "S1, all four
     * parts".
     */
    private String maternalNameChain() {
      return registry == null
          ? null
          : join(
              registry.nameArMother(),
              registry.nameArMotherFather(),
              registry.nameArMotherGrandfather(),
              registry.nameArMotherGreatGrandfather());
    }

    private String englishName() {
      return registry == null ? null : join(registry.firstNamesEn(), registry.lastNameEn());
    }

    private String effectiveSex() {
      if (registry != null && notBlank(registry.sexRegistry())) {
        return registry.sexRegistry();
      }
      return customer == null ? null : customer.sexDeclared();
    }

    /**
     * Grouped digits, in SDG. Grouped because an ungrouped nine-digit figure on a filing document
     * is a transcription error waiting to happen; {@code Locale.ROOT} because an Arabic locale
     * would render it in Arabic-Indic digits, and this cell is declared Latin.
     */
    private String monthlyExpenses() {
      if (customer == null || customer.monthlyExpensesSdg() == null) {
        return null;
      }
      // Grouped, and with the currency the approved page 2 shows. The artboard prints the figure
      // UNGROUPED ("45000 ج.س"); the product owner ruled on 2026-09-16 to keep the separator, the
      // mock reading as carelessness rather than intent on a money figure a branch officer reads
      // off paper. One of two deliberate departures from the artboards; the other is field 12's
      // gendered agreement, which the artboards show in one instance only.
      return String.format(Locale.ROOT, "%,d", customer.monthlyExpensesSdg()) + " ج.س";
    }

    /**
     * Every income source the customer picked, the primary one marked. Multi-select with exactly
     * one primary (field 20), so a form showing only the primary would lose real answers.
     */
    private String incomeSources() {
      List<IncomeSourceView> selected =
          customer == null || customer.incomeSources() == null
              ? List.of()
              : customer.incomeSources();
      if (selected.isEmpty()) {
        return null;
      }
      List<String> parts = new ArrayList<>();
      for (IncomeSourceView source : selected) {
        String label = reference(LIST_INCOME_SOURCE, source.sourceCode());
        if (label == null) {
          continue;
        }
        if (notBlank(source.otherText())) {
          label = label + " (" + source.otherText() + ")";
        }
        // The approved page 2's punctuation: «وظيفة — أساسي · أخرى». An em-dash introduces the
        // primary marker and a middle dot separates the sources, where this used to wrap the
        // marker in parentheses and join with «، ».
        parts.add(
            source.isPrimary() ? label + " — " + PrintedFormVocabulary.PRIMARY_SUFFIX : label);
      }
      return parts.isEmpty() ? null : String.join(" · ", parts);
    }
  }

  // ---- Small shared helpers ------------------------------------------------------------

  private static String formatDate(Instant instant, ZoneId zone) {
    return instant == null ? null : DATE.format(instant.atZone(zone));
  }

  private static String formatDate(LocalDate date) {
    return date == null ? null : DATE.format(date);
  }

  private static String join(String... parts) {
    List<String> present = new ArrayList<>();
    for (String part : parts) {
      if (notBlank(part)) {
        present.add(part.trim());
      }
    }
    return present.isEmpty() ? null : String.join(" ", present);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static Value arabic(String text) {
    return new Value(text, false);
  }

  private static Value latin(String text) {
    return new Value(text, true);
  }

  // latin(String, FieldOrigin) went with field 1, its only caller: the submission date is header
  // chrome on the approved design, not a numbered row, and no other field attaches its origin
  // inline.

  /**
   * A value on its way to becoming a {@link PrintedValue}: text that may be absent, the script it
   * is set in, and the origin the caller attaches. Exists so every field reads as one line and so
   * the absent case is decided in exactly one place — the rule that an absent value is never
   * attributed to anything is enforced by {@link PrintedValue} itself, but reaching it by
   * accidentally passing blank text to {@code PrintedValue.arabic} would be a construction failure
   * rather than an absence.
   */
  private record Value(String text, boolean latinScript, Optional<FieldOrigin> origin) {

    Value(String text, boolean latinScript) {
      this(text, latinScript, Optional.empty());
    }

    Value withOrigin(FieldOrigin newOrigin) {
      return new Value(text, latinScript, Optional.of(newOrigin));
    }

    PrintedValue toPrintedValue() {
      if (!notBlank(text)) {
        return PrintedValue.absent();
      }
      FieldOrigin attached =
          origin.orElseThrow(
              () -> new IllegalStateException("a present value must be given an origin"));
      return latinScript
          ? PrintedValue.latin(text.trim(), attached)
          : PrintedValue.arabic(text.trim(), attached);
    }
  }
}
