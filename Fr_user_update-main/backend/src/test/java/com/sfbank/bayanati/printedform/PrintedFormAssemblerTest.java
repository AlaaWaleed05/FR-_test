package com.sfbank.bayanati.printedform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.operator.domain.ChannelStateView;
import com.sfbank.bayanati.operator.domain.CustomerDataView;
import com.sfbank.bayanati.operator.domain.FaceResultView;
import com.sfbank.bayanati.operator.domain.IncomeSourceView;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.RegistryResultView;
import com.sfbank.bayanati.operator.domain.SalaryCertificateState;
import com.sfbank.bayanati.operator.domain.ScanResultView;
import com.sfbank.bayanati.operator.domain.StatusHistoryEntryView;
import com.sfbank.bayanati.printedform.config.FopFactoryProvider;
import com.sfbank.bayanati.printedform.domain.FieldOrigin;
import com.sfbank.bayanati.printedform.domain.PrintedChip;
import com.sfbank.bayanati.printedform.domain.PrintedField;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedFormImageSlot;
import com.sfbank.bayanati.printedform.domain.PrintedImage;
import com.sfbank.bayanati.printedform.domain.PrintedSection;
import com.sfbank.bayanati.printedform.domain.PrintedValue;
import com.sfbank.bayanati.printedform.domain.ReferenceLabels;
import com.sfbank.bayanati.printedform.service.FoDocumentWriter;
import com.sfbank.bayanati.printedform.service.PrintedFormAssembler;
import com.sfbank.bayanati.printedform.service.PrintedFormRenderer;
import com.sfbank.bayanati.printedform.service.PrintedFormSources;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The assembler, exercised as CLAUDE.md's package rule requires: a plain JUnit test, no Spring
 * context, no database, no network and no clock.
 *
 * <p><strong>Every value here is synthetic and none resembles a real one</strong> — the name is a
 * placeholder, the account number is outside the seeded stub range, and the national and document
 * numbers are patterns of zeros. Same rule {@code SyntheticForm} states for the renderer's fixture.
 */
class PrintedFormAssemblerTest {

  private static final ZoneId KHARTOUM = ZoneId.of("Africa/Khartoum");

  /** What PrintedFormService resolves off the scan's alpha-3 codes before the assembler runs. */
  private static final Map<String, String> COUNTRY_NAMES = Map.of("SDN", "السودان");

  private static final Instant SUBMITTED = Instant.parse("2026-09-06T09:01:00Z");
  private static final Instant PRINTED = Instant.parse("2026-09-14T08:45:00Z");

  /**
   * SMS and email verified, WhatsApp declined — so fields 25 and 26 both print, and the WhatsApp
   * row proves a declined channel changes nothing about a phone the customer proved by SMS.
   */
  private static final List<ChannelStateView> VERIFIED_SMS_AND_EMAIL =
      List.of(
          new ChannelStateView(MessageChannel.SMS, ChannelState.VERIFIED, SUBMITTED),
          new ChannelStateView(MessageChannel.WHATSAPP, ChannelState.DECLINED, null),
          new ChannelStateView(MessageChannel.EMAIL, ChannelState.VERIFIED, SUBMITTED));

  /**
   * The labels a real catalog would return, pinned. Deliberately MISSING {@code occupation}'s code
   * so one test can prove the unresolvable-code fallback without a second fixture.
   */
  private static final ReferenceLabels LABELS =
      (listCode, itemCode) ->
          Optional.ofNullable(
              Map.of(
                      "branch:16", "الجمهورية",
                      "country:SD", "السودان",
                      "admin_division:KRT", "الخرطوم",
                      "admin_division:OMD", "أم درمان",
                      "education_level:7", "دراسات عليا",
                      "income_source:1", "راتب / أجر")
                  .get(listCode + ":" + itemCode));

  // ---- the shape the form is bound to ---------------------------------------------------------

  /**
   * THREE sections, the third carrying six sub-headings, in the approved artboards' order.
   *
   * <p>Nine until S9-03, in {@code field-provenance.md}'s order, under a comment saying the
   * artboards must not be copied for theirs. AD-021, AD-022 and the product owner's 2026-09-16
   * ruling reversed that: the approved design over-rules the paper form's running order. Asserted
   * as an exact list so a reordering fails loudly rather than silently.
   */
  @Test
  void theThreeSectionsAndSixSubHeadingsAreTheApprovedOrder() {
    List<PrintedSection> sections = assemble(digitalProfile()).sections();

    assertThat(sections.stream().map(PrintedSection::title))
        .containsExactly(
            "١ — بيانات الهوية",
            "٢ — التحقق من الهوية",
            "٣ — البيانات المُقدَّمة من العميل",
            "الحساب والفرع",
            "قنوات الاتصال",
            "البيانات الشخصية والاجتماعية",
            "المهنة والدخل",
            "عنوان السكن",
            "جهة العمل عنوانه");

    assertThat(sections.stream().filter(PrintedSection::isSubHeading).map(PrintedSection::title))
        .as("the six under section 3")
        .hasSize(6);
    assertThat(sections.get(2).breakBefore())
        .as("section 3 starts page 2, which is what makes the form two pages")
        .isTrue();
    assertThat(sections.stream().filter(PrintedSection::breakBefore)).hasSize(1);
  }

  /** The badges the approved headings carry, and the fact the rest carry none. */
  @Test
  void onlyTheTwoIdentitySectionsCarryABadge() {
    List<PrintedSection> sections = assemble(digitalProfile()).sections();

    assertThat(sections.get(0).badge()).isEqualTo("من السجل المدني");
    assertThat(sections.get(1).badge()).isEqualTo("من وثيقة الهوية والتحقق الحي");
    assertThat(sections.stream().skip(2).map(PrintedSection::badge)).containsOnlyNulls();
  }

  /**
   * Sections 1 and 2 are laid out COLUMN-MAJOR on the artboard, and the renderer pairs adjacent
   * fields two to a row — so the order that produces the artboard's grid is 5,8 / 6,9 / 7,21, not
   * ascending. Asserted because it looks like a mistake and is not.
   */
  @Test
  void theTwoColumnSectionsInterleaveTheirFieldsToMatchTheArtboardsGrid() {
    List<PrintedSection> sections = assemble(digitalProfile()).sections();

    assertThat(sections.get(0).fields().stream().map(PrintedField::number))
        .containsExactly(5, 8, 6, 9, 7, 21);
    assertThat(sections.get(1).fields().stream().map(PrintedField::number))
        .containsExactly(43, 46, 44, 47, 45, 48);
  }

  /**
   * The field set the approved form prints: 2 to 48, with three deliberate absences.
   *
   * <p>It was "exactly 1 to 50" until S9-03, and every part of that changed. Field 1 «التاريخ» is
   * now header chrome on every page rather than a numbered row; 49 «التوقيع» survives as an image
   * tile's caption; 50 «شهادة مرتب» appears on neither approved page, though the certificate still
   * rides in the same PDF. 51 was never printable and still is not — the type refuses it.
   *
   * <p>It is also no longer a fixed set: fields 25 and 26 appear only for a VERIFIED channel
   * (AD-022 ruling 4), so the assertion is a subset relation plus the three exclusions, not an
   * equality. This fixture verifies SMS and email, so both appear here.
   *
   * <p>A FOURTH absence joined them at AD-022 (m): exactly one of 13 «اسم الزوج» and 14 «اسم
   * الزوجة» is printed, never both, so the other number is legitimately missing from 2..48. This
   * fixture resolves MALE — its registry sex is {@code "m"} — so 14 is printed and 13 is not.
   */
  @Test
  void theFormPrintsFieldsTwoToFortyEightAndNeverOneFortyNineFiftyOrFiftyOne() {
    List<Integer> numbers = fieldNumbers(assemble(digitalProfile()));

    assertThat(numbers).doesNotContain(1, 49, 50, 51);
    assertThat(numbers).allSatisfy(number -> assertThat(number).isBetween(2, 48));
    assertThat(numbers)
        .as("a male profile prints «اسم الزوجة» (14) and not «اسم الزوج» (13) -- AD-022 (m)")
        .contains(14)
        .doesNotContain(13);
    assertThat(numbers)
        .as("every other field between 2 and 48 that this profile has a row for")
        .containsAll(
            java.util.stream.IntStream.rangeClosed(2, 48).boxed().filter(n -> n != 13).toList());
  }

  /**
   * BL-145's remaining half, and the reason this closes the item: the rule now lives in the TYPE,
   * so no assembler — this one or a later one — can reintroduce field 51. Until the assembler
   * existed the rule was held by a test fixture alone, and a fixture cannot constrain production
   * code.
   */
  @Test
  void fieldFiftyOneCannotBeConstructedAtAll() {
    assertThatThrownBy(
            () ->
                PrintedField.single(
                    51, "مستندات الهوية", PrintedValue.arabic("x", FieldOrigin.CUSTOMER)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NOT on the form");
  }

  /** No field prints two values. Product-owner ruling, 2026-09-14; the type refuses a second. */
  @Test
  void everyFieldCarriesExactlyOneValue() {
    assertThat(fields(assemble(digitalProfile())))
        .allSatisfy(field -> assertThat(field.values()).hasSize(1));
  }

  // ---- one source per field -------------------------------------------------------------------

  /**
   * The five Civil Registry fields carry the REGISTRY's values, not the scan's — which matters most
   * for field 7, where the scan holds an equal-by-guard copy and printing it would make the form's
   * provenance claim true only by coincidence.
   */
  @Test
  void fieldsFiveSixSevenNineAndTwentyOneComeFromTheCivilRegistry() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(digitalProfile()));

    assertThat(byNumber.get(5).value().text()).isEqualTo("محمد أحمد الطيب عبدالله");
    assertThat(byNumber.get(6).value().text()).isEqualTo("SAMPLE FULL NAME");
    assertThat(byNumber.get(7).value().text()).isEqualTo("000-0000-0001");
    assertThat(byNumber.get(9).value().text()).isEqualTo("ذكر");
    assertThat(byNumber.get(21).value().text()).isEqualTo("01/01/1990");

    assertThat(byNumber.get(7).value().origin()).contains(FieldOrigin.CIVIL_REGISTRY);
    // The scan's own identity number is DIFFERENT in this fixture on purpose: if the assembler
    // read the scan, this assertion is what catches it.
    assertThat(byNumber.get(7).value().text()).isNotEqualTo("SCAN-COPY-0001");
  }

  /** Field 8 is the maternal chain, all four parts, matching the paternal chain. */
  @Test
  void theMothersNameIsAllFourPartsNotOne() {
    assertThat(byNumber(assemble(digitalProfile())).get(8).value().text())
        .isEqualTo("فاطمة علي حسن محمد");
  }

  /** Fields 44-48 are the scanned document's, tagged with the DOCUMENT and never with Uqudo. */
  @Test
  void theIdentityDocumentFieldsAreTaggedWithTheDocumentNotTheVendor() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(digitalProfile()));

    assertThat(byNumber.get(44).value().origin()).contains(FieldOrigin.PASSPORT);
    assertThat(byNumber.get(44).value().text()).isEqualTo("P00000001");
    assertThat(byNumber.get(45).value().text()).isEqualTo("01/01/2021");
    assertThat(byNumber.get(47).value().text()).isEqualTo("01/01/2031");
    assertThat(FieldOrigin.PASSPORT.tag()).isEqualTo("جواز سفر");
  }

  /**
   * The seven home-address rows are the CUSTOMER's, and the Civil Registry's own address string is
   * not on the form at all — the product owner confirmed that consequence explicitly on 2026-09-14.
   * Asserted by looking for the registry's string ANYWHERE on the form, which is the only way to
   * catch it reappearing on some other row.
   */
  @Test
  void theCivilRegistrysAddressStringIsNowhereOnTheForm() {
    PrintedFormDocument document = assemble(digitalProfile());

    assertThat(fields(document).stream().map(field -> field.value().text()))
        .noneMatch(text -> text.contains("عنوان السجل المدني القديم"));
    assertThat(byNumber(document).get(35).value().text()).isEqualTo("السودان");
    assertThat(byNumber(document).get(39).value().text()).isEqualTo("أم درمان");
  }

  // ---- absence ---------------------------------------------------------------------------------

  /**
   * An absent value prints «غير متاح» and carries NO origin. A value cannot be simultaneously
   * unavailable and read from a passport, from the registry, or from the customer's own entry.
   */
  @Test
  void anAbsentValueIsNeverAttributedToAnySource() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(digitalProfile()));

    // 14 is the wife's name on a male, single customer; 42 is a house number this fixture omits.
    // This used to probe 13 on the same fixture. Since AD-022 (m) a male profile prints 14 and not
    // 13, so 14 is now the row that carries the absence -- an unmarried customer still gets their
    // one spouse row reading «غير متاح», which is the half of the old behaviour the ruling kept.
    assertThat(byNumber.get(14).value().isAbsent()).isTrue();
    assertThat(byNumber.get(14).value().text()).isEqualTo(PrintedValue.ABSENT_TEXT);
    assertThat(byNumber.get(14).value().origin()).isEmpty();
    assertThat(byNumber.get(42).value().origin()).isEmpty();
  }

  /**
   * A manually completed profile has no registry result at all, and the form must show that as an
   * ABSENCE rather than a blank — ticket 04 decision 7's second half. A blank cell beside a
   * customer value reads as "the registry agreed", which is the one misreading this form must not
   * invite.
   *
   * <p>No fallback to the scanned document, and that is not an omission: the product owner closed
   * the question on 2026-09-14 by pointing out that where there is no registry result there is no
   * Uqudo document either, so the fallback had nothing to fall back to.
   */
  @Test
  void aManuallyCompletedProfilePrintsTheRegistryFieldsAsAbsentRatherThanBlank() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(manualProfile()));

    for (int registryField : List.of(5, 6, 7, 8, 9, 21)) {
      assertThat(byNumber.get(registryField).value().isAbsent())
          .as("field %d on a profile the registry never answered for", registryField)
          .isTrue();
    }
  }

  // ---- the one form -------------------------------------------------------------------------

  /**
   * An EDITED field is marked and the operator's name is WITHHELD.
   *
   * <p>Rewritten at S9-03. It used to assert that every customer-entered field on a manually
   * COMPLETED profile was marked, which was the only granularity the schema could support — one
   * flag for the whole profile. The marker now reads {@code app.profile_field_edit}, so the fixture
   * says which fields an operator actually keyed and the assertion is about those alone.
   *
   * <p>The withheld name is the half that did not change: no filed form discloses which member of
   * staff touched which field. The footer names the operator who PRINTED it, a different disclosure
   * and the one the product owner judged sufficient.
   */
  @Test
  void anEditedFieldIsMarkedAndTheOperatorIsNotNamed() {
    Map<Integer, PrintedField> byNumber =
        byNumber(assemble(digitalProfile(), false, null, Set.of(10), COUNTRY_NAMES));

    assertThat(byNumber.get(10).edited()).isTrue();
    assertThat(byNumber.get(10).editedBy()).isNull();
  }

  /**
   * Nothing is marked when nothing was edited, and an edit marks ONLY the field it names.
   *
   * <p>The second half is what stops the marker degenerating back into "the whole page was
   * touched", which is what it did before the per-field storage existed.
   */
  @Test
  void anUneditedProfileMarksNothingAndAnEditMarksOnlyItsOwnField() {
    assertThat(fields(assemble(digitalProfile()))).noneMatch(PrintedField::edited);

    Map<Integer, PrintedField> byNumber =
        byNumber(assemble(digitalProfile(), false, null, Set.of(10), COUNTRY_NAMES));
    assertThat(byNumber.get(10).edited()).as("the edited field").isTrue();
    assertThat(byNumber.get(11).edited()).as("its neighbour").isFalse();
    assertThat(byNumber.get(5).edited()).as("a registry field, never editable at all").isFalse();
  }

  /**
   * Rows 13 and 14 share one {@code spouse_name} column and an edit is recorded against 13 alone.
   *
   * <p>{@code EditableField.SPOUSE_NAME} carries field number 13, but the form prints EITHER «اسم
   * الزوج» (13) or «اسم الزوجة» (14) from that one column, whichever the customer's sex calls for —
   * 13 for a married WOMAN, naming her husband, and 14 for a married MAN, naming his wife. Both
   * were printed until AD-022 (m); since then the unselected number is not on the form at all.
   *
   * <p>So a marker keyed on the stored number alone marks a married woman's row and silently misses
   * a married man's: the same column, the same edit, a marker or no marker depending only on the
   * customer's sex. The married man is the case that fails, and he is the reason for the second
   * half of this test. Only one of the two rows ever carries a value, so treating an edit of 13 as
   * an edit of both marks exactly the row with something in it.
   */
  @Test
  void editingASpouseNameMarksWhicheverOfTheTwoRowsCarriesIt() {
    Map<Integer, PrintedField> marriedWoman =
        byNumber(assemble(withMarriedFemale(), false, null, Set.of(13), COUNTRY_NAMES));

    assertThat(marriedWoman.get(13).value().isAbsent())
        .as("a married woman's husband is named on row 13")
        .isFalse();
    assertThat(marriedWoman.get(13).edited()).as("which the stored number marks directly").isTrue();

    // THE CASE A NUMBER-KEYED MARKER MISSES. His wife's name is on row 14 and the edit is recorded
    // against 13, so without the expansion this row prints an operator-keyed value with no marker.
    // Since AD-022 (m) row 14 is his ONLY spouse row, so the expansion is now the whole of what
    // stands between him and an unmarked operator-keyed value -- not a redundant second marking.
    Map<Integer, PrintedField> marriedMan =
        byNumber(assemble(marriedMale(), false, null, Set.of(13), COUNTRY_NAMES));

    assertThat(marriedMan.get(14).value().isAbsent())
        .as("a married man's wife is named on row 14")
        .isFalse();
    assertThat(marriedMan.get(14).edited()).as("and row 14 must carry the marker too").isTrue();
    assertThat(marriedMan.keySet())
        .as("and row 13 is not on his form at all to stay unmarked -- AD-022 (m)")
        .doesNotContain(13);
  }

  /**
   * An ABSENT field is never marked manual. Found at review, and it is ticket 04's round-two
   * correction 3 arriving by a different door: {@link PrintedValue} refuses to construct a tagged
   * absence, but the «يدوي» marker sits on the FIELD rather than on the value, so it walked
   * straight around that guard and printed «غير متاح يدوي — op-manual-1».
   *
   * <p>A manually completed profile is exactly where it showed: it is printable (manual completion
   * sets status {@code submitted}) and it is the only profile the marker applies to at all. Field
   * 14 is the wife's name, unnamed on this single male customer; 42 a house number this fixture
   * omits.
   *
   * <p>Field 13 was a third case here until AD-022 (m). It is now not printed at all on a male
   * profile, so it moved out of the loop and into an explicit absence assertion — an unprinted row
   * cannot be wrongly marked, and asserting over a null map entry would only NPE.
   */
  @Test
  void anAbsentFieldIsNeverMarkedManual() {
    Map<Integer, PrintedField> byNumber =
        byNumber(assemble(manualProfile(), false, null, Set.of(13, 14, 42), COUNTRY_NAMES));
    for (int absentField : List.of(14, 42)) {
      assertThat(byNumber.get(absentField).value().isAbsent()).isTrue();
      assertThat(byNumber.get(absentField).edited())
          .as("field %d is absent on a manual profile", absentField)
          .isFalse();
      assertThat(byNumber.get(absentField).editedBy()).isNull();
    }
    assertThat(byNumber.keySet())
        .as("row 13 is not printed on a male profile at all -- AD-022 (m)")
        .doesNotContain(13);
  }

  /**
   * Fields 4 and 48 print the COUNTRY'S ARABIC NAME, resolved from the MRZ's alpha-3 code.
   *
   * <p>Reversed at S9-03 (product-owner ruling 2026-09-16, following the approved artboards, which
   * print «السودان»). This used to assert the raw «SDN», on the assembler's own stated reasoning
   * that the country list is keyed on alpha-2 and no alpha-3 mapping existed. BL-157 was closed by
   * finding that V0022 seeds {@code alpha3} into every country row, so the mapping is
   * server-supplied and version-pinned rather than invented.
   *
   * <p>The attribution is unchanged and still the DOCUMENT's: the display ruling lists field 4
   * under "customer entry only", but no customer-entered nationality column exists anywhere in the
   * schema.
   */
  @Test
  void theNationalityAndIssuingCountryResolveToTheirArabicNames() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(digitalProfile()));

    assertThat(byNumber.get(4).value().text()).isEqualTo("السودان");
    assertThat(byNumber.get(4).value().latinScript())
        .as("a resolved name is Arabic script, so the bidi wrapper matches what prints")
        .isFalse();
    assertThat(byNumber.get(4).value().origin()).contains(FieldOrigin.PASSPORT);
    assertThat(byNumber.get(48).value().text()).isEqualTo("السودان");
  }

  /**
   * An alpha-3 the country list does not know prints AS RECEIVED, not as «غير متاح».
   *
   * <p>ICAO issues document codes with no ISO country row at all — {@code XXA} for a stateless
   * person's travel document, {@code GBD} for a British overseas citizen. The bank holds a real
   * code a branch officer can look up, so passing it through is a true statement and «غير متاح»
   * would be a false one. Same rule the reference-code fallback already follows for every other
   * coded field.
   */
  @Test
  void anUnknownAlpha3PrintsAsReceived() {
    Map<Integer, PrintedField> byNumber =
        byNumber(assemble(digitalProfile(), false, null, Set.of(), Map.of()));

    assertThat(byNumber.get(4).value().text()).isEqualTo("SDN");
    assertThat(byNumber.get(4).value().latinScript())
        .as("an unresolved code stays Latin script")
        .isTrue();
  }

  /**
   * The operator who PRINTED is named on the form. This was true of both variants before AD-022
   * collapsed them, which is why removing the variant choice costs no printer attribution.
   */
  @Test
  void theFormNamesTheOperatorWhoPrinted() {
    assertThat(assemble(digitalProfile()).printedBy()).isEqualTo("op-print-1");
  }

  // ---- images
  // ------------------------------------------------------------------------------------

  /**
   * Five boxes, always, in layout order — an absent slot becomes an ABSENT image rather than being
   * dropped. Dropping it would reflow the grid and silently hide that the bank holds no such image;
   * the box printing «غير متاح» is what makes the absence visible.
   */
  @Test
  void allFiveImageBoxesArePresentAndAMissingArtifactBecomesAnAbsentBox() {
    List<PrintedImage> images = assemble(digitalProfile()).images();

    assertThat(images).hasSize(5);
    assertThat(images.stream().map(PrintedImage::caption))
        .containsExactly("السجل المدني", "صورة الوثيقة", "وثيقة الهوية", "التوقيع", "إثبات الحياة");
    // The fixture supplies four of the five; the registry portrait is deliberately absent, and
    // since S9-03 it is the FIRST tile, the approved page 1 leading with it.
    assertThat(images.get(0).isPresent()).isFalse();
    assertThat(images.get(2).isPresent()).isTrue();
  }

  /**
   * FIVE images in the bundle, FOUR tiles on the page — the liveness frame is the one left off.
   *
   * <p>Product-owner ruling 2026-09-16 under AD-022: the approved page 1 shows four tiles and the
   * liveness frame is not among them, while the frame is still captured, still stored and still
   * printed as its own appended sheet. The assembler is the only thing that decides this, and
   * without this test the whole ruling reduces to one un-asserted ternary — deleting it leaves
   * every test green and puts five tiles on every printed form.
   */
  @Test
  void theLivenessFrameIsTheOnlyImageKeptOffThePage() {
    List<PrintedImage> images = assemble(digitalProfile()).images();

    assertThat(images.get(4).caption()).as("the liveness frame's slot").isEqualTo("إثبات الحياة");
    assertThat(images.get(4).onPage()).as("and it is off the page-1 grid").isFalse();
    assertThat(images.get(4).isPresent()).as("while staying in the bundle").isTrue();

    assertThat(images.stream().filter(PrintedImage::onPage))
        .as("the other four keep their tiles, the absent registry portrait included")
        .hasSize(4);
  }

  /**
   * The five slots, each bound to the artifact kind it reads and the caption it prints.
   *
   * <p><strong>What this does NOT prove, stated because an earlier version of this javadoc claimed
   * it did:</strong> that the captions still match the back office's. They are copies of {@code
   * backoffice/src/profiles/artifactTiles.ts}'s {@code TILE_CAPTIONS_AR} and nothing in this
   * codebase reads that file, so editing the TypeScript alone fails nothing here. This is a guard
   * against the SERVER side drifting, and a place for the next reader to learn there is a second
   * copy — not a gate across the two tiers. They do currently match.
   */
  @Test
  void eachImageSlotBindsOneArtifactKindToOneCaption() {
    assertThat(PrintedFormImageSlot.artifactKinds())
        .containsExactly(
            "portrait_registry", "portrait_uqudo", "doc_front", "signature", "face_audit_trail");
    assertThat(PrintedFormImageSlot.inFormOrder().stream().map(PrintedFormImageSlot::caption))
        .containsExactly("السجل المدني", "صورة الوثيقة", "وثيقة الهوية", "التوقيع", "إثبات الحياة");
  }

  // ---- the attachments opt-in
  // ----------------------------------------------------------------------

  /**
   * The privacy-relevant direction: the profile HAS a certificate and the operator said no. Failing
   * this prints a customer's pay document into a bundle nobody asked to include it in.
   */
  @Test
  void aCertificateIsDroppedWhenTheOperatorDeclinedTheAttachments() {
    PrintedFormDocument declined = assemble(digitalProfile(), false, certificate());
    PrintedFormDocument accepted = assemble(digitalProfile(), true, certificate());

    assertThat(declined.salaryCertificate()).isNull();
    assertThat(declined.includeAttachments()).isFalse();
    assertThat(accepted.salaryCertificate()).isNotNull();
  }

  // fieldFiftySaysACertificateExistsEvenWhenTheOperatorDeclinedToPrintIt is GONE with field 50.
  // «شهادة مرتب» is not a row on either approved page. The certificate itself still rides in the
  // same PDF when the operator asks for attachments, which PrintedFormServiceTest covers; what was
  // removed is the row that announced it.

  // ---- reference labels
  // ------------------------------------------------------------------------------

  /**
   * An unresolvable code prints the CODE, not «غير متاح». The bank holds a real value and a branch
   * officer can look a number up; "not available" would be a statement about the customer that is
   * simply false. The fixture's label map omits the occupation code on purpose.
   */
  @Test
  void anUnresolvableReferenceCodeFallsBackToTheCodeRatherThanToAnAbsence() {
    PrintedField occupation = byNumber(assemble(digitalProfile())).get(18);

    assertThat(occupation.value().isAbsent()).isFalse();
    assertThat(occupation.value().text()).isEqualTo("25");
  }

  /** Coded address levels resolve to their Arabic labels; a free-text level falls through to it. */
  @Test
  void aCodedAddressLevelResolvesAndAFreeTextOneFallsThrough() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(digitalProfile()));

    assertThat(byNumber.get(36).value().text()).isEqualTo("الخرطوم");
    assertThat(byNumber.get(29).value().text()).isEqualTo("ولاية بنص حر");
  }

  // ---- the awkward singles
  // ------------------------------------------------------------------------

  /**
   * ONE {@code spouse_name} column, ONE printed row. A married man's wife is named against «اسم
   * الزوجة» and not against «اسم الزوج», and the registry's sex is what decides.
   */
  @Test
  void theSpouseNameLandsOnTheRowTheCustomersSexSelects() {
    ProfileDetail married = withMarriedFemale();
    Map<Integer, PrintedField> male = byNumber(assemble(marriedMale()));
    Map<Integer, PrintedField> female = byNumber(assemble(married));

    assertThat(male.get(14).value().text()).isEqualTo("زوجة تجريبية");
    assertThat(female.get(13).value().text()).isEqualTo("زوجة تجريبية");
  }

  /**
   * BL-164 / AD-022 (m). The row the sex does NOT select is not printed AT ALL — it is absent from
   * the document, not present-and-empty.
   *
   * <p>This is the assertion that cannot pass against the old behaviour, and it is deliberately
   * about ABSENCE FROM THE MAP rather than about {@code value().isAbsent()}. The form emitted both
   * rows until 2026-09-18, so the old code produced a field 14 whose value was absent — a test
   * asserting only {@code isAbsent()} passes against the defect and against the fix alike, which is
   * how the two-row form survived every review that looked at this area.
   *
   * <p>The unmarried case is the other half and is asserted here too, because it is the edge the
   * ruling deliberately did NOT change: a single customer still gets exactly one row, the one their
   * sex selects, reading «غير متاح». A fix that suppressed empty rows as well would pass the
   * married assertions and quietly delete a row the bank's form is supposed to carry. The fixture
   * is {@code digitalProfile()}, whose REGISTRY sex is {@code "m"}, so the row is 14 — the sex is
   * named here because "exactly one row" is only meaningful once you know which.
   */
  @Test
  void theRowTheSexDoesNotSelectIsNotPrintedAtAll() {
    Map<Integer, PrintedField> male = byNumber(assemble(marriedMale()));
    Map<Integer, PrintedField> female = byNumber(assemble(withMarriedFemale()));

    assertThat(male)
        .as("a married man's form carries «اسم الزوجة» and no «اسم الزوج»")
        .containsKey(14);
    assertThat(male.keySet()).as("row 13 is not on his form at all").doesNotContain(13);

    assertThat(female)
        .as("a married woman's form carries «اسم الزوج» and no «اسم الزوجة»")
        .containsKey(13);
    assertThat(female.keySet()).as("row 14 is not on her form at all").doesNotContain(14);

    // The unmarried edge: one row, the one his sex selects, printing «غير متاح» exactly as before.
    Map<Integer, PrintedField> single = byNumber(assemble(digitalProfile()));
    assertThat(single.keySet())
        .as("exactly one spouse row, whatever the marital status")
        .containsAnyOf(13, 14);
    assertThat(single.containsKey(13) && single.containsKey(14)).as("never both").isFalse();
    assertThat(single.get(14).value().isAbsent())
        .as("and it still reads «غير متاح» -- suppressing EMPTY rows is not what AD-022 (m) ruled")
        .isTrue();
  }

  /** Multi-select with exactly one primary: every source prints, and the primary is marked. */
  @Test
  void everyIncomeSourcePrintsAndThePrimaryIsMarked() {
    assertThat(byNumber(assemble(digitalProfile())).get(20).value().text())
        .isEqualTo("راتب / أجر — أساسي");
  }

  /** Grouped digits, Latin, never Arabic-Indic — the cell is declared Latin script. */
  @Test
  void monthlyExpensesAreGroupedInLatinDigits() {
    PrintedValue expenses = byNumber(assemble(digitalProfile())).get(19).value();

    // Grouped AND carrying the currency: one of S9-03's two deliberate departures from the
    // artboards, which print the figure ungrouped.
    assertThat(expenses.text()).isEqualTo("76,000 ج.س");
    assertThat(expenses.latinScript()).isTrue();
  }

  /**
   * Dates are {@code DD/MM/YYYY HH:mm} in Africa/Khartoum, never ISO and never in UTC. The fixture
   * submits at 09:01 UTC, which is 11:01 in Khartoum — so a formatter that forgot the zone prints
   * 09:01 and this catches it.
   */
  @Test
  void timestampsAreKhartoumLocalInTheSystemsOwnDateShape() {
    PrintedFormDocument document = assemble(digitalProfile());

    assertThat(document.submittedAt()).isEqualTo("06/09/2026 11:01");
    assertThat(document.printedAt()).isEqualTo("14/09/2026 10:45");
    assertThat(byNumber(document)).as("field 1 is header chrome, not a row").doesNotContainKey(1);
  }

  /**
   * Ticket 04 decision 5: no redaction. A redacted form cannot serve as a branch filing document.
   */
  @Test
  void theAccountNumberAndNationalNumberArePrintedInFull() {
    Map<Integer, PrintedField> byNumber = byNumber(assemble(digitalProfile()));

    assertThat(byNumber.get(3).value().text()).isEqualTo("0000009999");
    assertThat(byNumber.get(7).value().text()).isEqualTo("000-0000-0001");
    assertThat(byNumber.get(25).value().text()).isEqualTo("+249912345678");
  }

  /**
   * Field 12 agrees with the customer's sex.
   *
   * <p>Product-owner ruling 2026-09-16, following the approved page 2, which prints «متزوجة». A
   * bank form that addresses a married woman as «متزوج» is a visible defect on a document she may
   * be handed. The agreement follows the same sex the spouse-name row does — the registry's,
   * falling back to the declared value — so the two can never disagree with each other.
   */
  @Test
  void maritalStatusAgreesWithTheCustomersSex() {
    assertThat(byNumber(assemble(withMarriedFemale())).get(12).value().text()).isEqualTo("متزوجة");
    assertThat(byNumber(assemble(marriedMale())).get(12).value().text()).isEqualTo("متزوج");
  }

  /**
   * AD-022 ruling 4: only a VERIFIED channel prints, and an unverified one leaves no trace.
   *
   * <p>Not «غير متاح» — absent. The ruling is that a declined or unverified channel is "not shown
   * at all, not greyed, not tagged", because a form carrying an unproved contact detail puts it in
   * front of a branch officer who would then use it.
   */
  @Test
  void onlyVerifiedContactChannelsPrint() {
    Map<Integer, PrintedField> none = byNumber(assemble(profileWithChannels(List.of())));
    assertThat(none).as("nothing verified: neither row exists").doesNotContainKeys(25, 26);

    Map<Integer, PrintedField> emailOnly =
        byNumber(
            assemble(
                profileWithChannels(
                    List.of(
                        new ChannelStateView(MessageChannel.SMS, ChannelState.UNVERIFIED, null),
                        new ChannelStateView(
                            MessageChannel.EMAIL, ChannelState.VERIFIED, SUBMITTED)))));
    assertThat(emailOnly).doesNotContainKey(25).containsKey(26);
  }

  /**
   * The phone backs TWO channels, so field 25 prints when EITHER is verified.
   *
   * <p>They are the same number. A customer who proved it by WhatsApp has proved it, and a form
   * that demanded SMS specifically would drop a verified phone — the defect this exists to catch.
   */
  @Test
  void aPhoneVerifiedByEitherChannelPrints() {
    for (MessageChannel channel : List.of(MessageChannel.SMS, MessageChannel.WHATSAPP)) {
      Map<Integer, PrintedField> byNumber =
          byNumber(
              assemble(
                  profileWithChannels(
                      List.of(new ChannelStateView(channel, ChannelState.VERIFIED, SUBMITTED)))));

      assertThat(byNumber).as("a phone verified by %s prints", channel).containsKey(25);
      assertThat(byNumber.get(25).value().text()).isEqualTo("+249912345678");
    }
  }

  /**
   * The test is on STATE, never on {@code verifiedAt}. A row can carry a timestamp from an earlier
   * cycle while its current state is not verified, and reading the timestamp would print a number
   * the bank has not proved THIS time.
   */
  @Test
  void aStaleVerifiedAtDoesNotMakeAChannelVerified() {
    Map<Integer, PrintedField> byNumber =
        byNumber(
            assemble(
                profileWithChannels(
                    List.of(
                        new ChannelStateView(
                            MessageChannel.SMS, ChannelState.UNVERIFIED, SUBMITTED),
                        new ChannelStateView(
                            MessageChannel.EMAIL, ChannelState.DECLINED, SUBMITTED)))));

    assertThat(byNumber).doesNotContainKeys(25, 26);
  }

  /** The digital profile with a specific set of channel states. */
  private static ProfileDetail profileWithChannels(List<ChannelStateView> channels) {
    return profile(
        "digital", registry(), scan(), customer("single", null, "m"), List.of(), channels);
  }

  /**
   * THE VISUAL PROOF for the approved three-section form — the only way a human can check this
   * against the artboards on a machine that cannot print a real profile.
   *
   * <p>BL-146 is why it exists here rather than through the app: {@code StubUqudoClient} stores the
   * ASCII string {@code fake-image-bytes:<id>} under a declared {@code image/jpeg}, and the
   * renderer correctly refuses a render it cannot decode, so a locally-run stack answers a print
   * with a 500 the moment the profile has images. This builds the SAME document the service would
   * assemble and hands it real PNG swatches, so the pages can be looked at.
   *
   * <p>Writes {@code target/printed-form/approved-form.pdf} and one PNG per page. It asserts only
   * what a machine can honestly check — the page count, and that the tiles are on page 1 — because
   * everything else the artboards govern is a matter of where things sit, which extracted text
   * cannot answer.
   */
  @Test
  void rendersTheApprovedFormForEyesOn() throws Exception {
    Map<PrintedFormImageSlot, byte[]> images = new EnumMap<>(PrintedFormImageSlot.class);
    images.put(PrintedFormImageSlot.REGISTRY_PORTRAIT, SyntheticForm.swatch(120, 150));
    images.put(PrintedFormImageSlot.DOCUMENT_PORTRAIT, SyntheticForm.swatch(120, 150));
    images.put(PrintedFormImageSlot.DOCUMENT, SyntheticForm.swatch(190, 120));
    images.put(PrintedFormImageSlot.SIGNATURE, SyntheticForm.swatch(300, 110));
    images.put(PrintedFormImageSlot.LIVENESS, SyntheticForm.swatch(130, 130));

    PrintedFormDocument document =
        PrintedFormAssembler.assemble(
            new PrintedFormSources(
                withMarriedFemale(),
                LABELS,
                images,
                null,
                false,
                "op-print-1",
                Set.of(10, 38),
                COUNTRY_NAMES,
                PRINTED,
                KHARTOUM));

    java.nio.file.Path out = java.nio.file.Path.of("target", "printed-form");
    java.nio.file.Files.createDirectories(out);
    byte[] pdf =
        new PrintedFormRenderer(FopFactoryProvider.create(), new FoDocumentWriter())
            .render(document);
    java.nio.file.Files.write(out.resolve("approved-form.pdf"), pdf);

    try (org.apache.pdfbox.pdmodel.PDDocument rendered = org.apache.pdfbox.Loader.loadPDF(pdf)) {
      org.apache.pdfbox.rendering.PDFRenderer rasteriser =
          new org.apache.pdfbox.rendering.PDFRenderer(rendered);
      for (int page = 0; page < rendered.getNumberOfPages(); page++) {
        javax.imageio.ImageIO.write(
            rasteriser.renderImageWithDPI(page, 130),
            "PNG",
            out.resolve("approved-form-page-" + (page + 1) + ".png").toFile());
      }

      StringBuilder map = new StringBuilder();
      for (int page = 1; page <= rendered.getNumberOfPages(); page++) {
        org.apache.pdfbox.text.PDFTextStripper one = new org.apache.pdfbox.text.PDFTextStripper();
        one.setStartPage(page);
        one.setEndPage(page);
        map.append("=== PAGE ")
            .append(page)
            .append(" ===")
            .append(System.lineSeparator())
            .append(one.getText(rendered));
      }
      java.nio.file.Files.writeString(
          out.resolve("page-map.txt"), map.toString(), java.nio.charset.StandardCharsets.UTF_8);

      // THE TILES ARE ON PAGE 1, which is the property S9-03's commit 4 had to restore: the grid
      // was written after every section, so section 3's new page break pushed it onto page 2.
      //
      // Anchored on a TILE CAPTION since commit 5. It used to look for «الصور والتوقيع», the band
      // that headed the grid, and the approved page 1 has no such heading — so the band went and
      // this assertion had to be re-pointed rather than dropped, or the only proof that the tiles
      // land on page 1 would have gone with the heading.
      org.apache.pdfbox.text.PDFTextStripper firstPage =
          new org.apache.pdfbox.text.PDFTextStripper();
      firstPage.setStartPage(1);
      firstPage.setEndPage(1);
      String pageOne = firstPage.getText(rendered);
      assertThat(pageOne).as("the signature tile is on page 1").contains("التوقيع");
      assertThat(pageOne)
          .as("and the band that used to head the grid is gone")
          .doesNotContain("الصور والتوقيع");

      // The two verification chips, which had no assembler output at all until commit 5 and are
      // artboard content on page 1. Asserted on the FULL chip text: «التحقق الحي» alone is a
      // substring of section 2's badge «من وثيقة الهوية والتحقق الحي», so a looser assertion
      // passes against the badge and proves nothing about the chip.
      assertThat(pageOne)
          .as("the liveness chip, derived from the presence of a face result")
          .contains("التحقق الحي — ناجح");
      assertThat(pageOne).as("the MRZ chip").contains("تحقق MRZ — صحيح");

      // AD-022 ruling 3 is a DISPLAY ruling and this is the page it governs: the match is still
      // run, stored and audited, and no figure of it may reach paper.
      assertThat(pageOne)
          .as("no face-match result anywhere on the form")
          .doesNotContain("مطابقة الوجه", "درجة", "الثقة");

      // TWO PAGES, which is BL-162 closed and the approved design met.
      //
      // Commit 4 left this asserting 2..3 with the reason, because the form ran to three: section 3
      // overflowed page 2 by two rows and page 3 carried nothing but its own chrome. Worth
      // recording how it actually closed, because the obvious answer was wrong. Deleting the
      // identity band reclaims space on PAGE 1, and section 3 starts page 2 behind a hard
      // break-before, so that space was never what was scarce. What paid for the two rows was the
      // single 17mm header (the artboards' own, replacing a 22mm continuation header), the
      // artboard's page margins, and bringing the field VALUE size back from 9.5pt toward the
      // 7.5pt every artboard row actually uses.
      assertThat(rendered.getNumberOfPages())
          .as("the approved design is two pages, and BL-162 is that the form ran to three")
          .isEqualTo(2);
    }
  }

  // ---- the two verification chips (AD-022 ruling 3)
  // -------------------------------------------

  @Test
  void theLivenessChipIsDerivedFromThePresenceOfAFaceResultAndNeverFromItsValues() {
    List<PrintedChip> chips =
        PrintedFormAssembler.assemble(sources(withMarriedFemale())).verificationChips();

    assertThat(chips).extracting(PrintedChip::text).first().isEqualTo("التحقق الحي — ناجح");
    assertThat(chips.get(0).tone()).isEqualTo(PrintedChip.Tone.GOOD);
  }

  /**
   * THE ONLY TEST THAT TELLS THE TWO DERIVATIONS APART, and without it the suite cannot.
   *
   * <p>Every other fixture in this class builds its face result as {@code scan == null ? null : new
   * FaceResultView(true, 5, 4, true, …)}, so "a row exists" and "{@code passed} is true" are
   * perfectly correlated across the whole suite. An assembler reading {@code faceResult().passed()}
   * — the exact mistake {@link PrintedChip}'s javadoc warns about, and the one AD-022 ruling 3
   * forbids — passes every one of them, including the presence test above and the
   * never-reached-liveness test below, which flips PRESENCE rather than the verdict.
   *
   * <p>So this profile has a face result that FAILED its match: {@code match=false, matchLevel=2}
   * against a threshold of 4, hence {@code passed=false}. The liveness chip must still read «ناجح»,
   * because the customer demonstrably got through stage 10 — the JWS that wrote this row is the
   * proof, and V0008 records that a liveness failure produces no JWS at all. A form that said «لم
   * يتم بعد» here would be reporting the face match under a liveness label, which is both wrong and
   * a breach of the ruling.
   */
  @Test
  void aFailedFaceMatchDoesNotChangeTheLivenessChip() {
    ProfileDetail failedMatch =
        profileWithFaceResult(
            withMarriedFemale(), new FaceResultView(false, 2, 4, false, SUBMITTED));

    List<PrintedChip> chips =
        PrintedFormAssembler.assemble(sources(failedMatch)).verificationChips();

    assertThat(chips.get(0).text())
        .as("liveness is the row's PRESENCE; the match verdict is a different question")
        .isEqualTo("التحقق الحي — ناجح");
    assertThat(chips.get(0).tone()).isEqualTo(PrintedChip.Tone.GOOD);

    // And the failure it does carry reaches neither chip nor page.
    assertThat(chips).extracting(PrintedChip::text).noneMatch(text -> text.contains("فشل"));
    assertThat(new FoDocumentWriter().write(PrintedFormAssembler.assemble(sources(failedMatch))))
        .as("a failed match is still run, stored and audited — and never printed")
        .doesNotContain("فشل");
  }

  /**
   * AD-022 ruling 3 is a DISPLAY ruling, and this is the assertion that enforces it.
   *
   * <p>The fixture's face result is {@code match=true, matchLevel=5, threshold=4, passed=true}, so
   * every figure that must not print has a distinctive value to look for. Reading {@code passed}
   * instead of the row's PRESENCE would produce the identical chip on this profile — which is why
   * the companion test below flips the profile rather than the figures.
   */
  @Test
  void noFaceMatchFigureReachesTheForm() {
    PrintedFormDocument document = PrintedFormAssembler.assemble(sources(withMarriedFemale()));
    String fo = new FoDocumentWriter().write(document);

    assertThat(fo)
        .as("no match level, threshold or verdict anywhere in the form")
        .doesNotContain("مطابقة الوجه", "درجة المطابقة", "الثقة");
    // No digit reaches a chip. The fixture's matchLevel is 5 and its threshold 4, so a chip that
    // had leaked either would say so here.
    assertThat(document.verificationChips())
        .extracting(PrintedChip::text)
        .noneMatch(text -> text.chars().anyMatch(Character::isDigit));
  }

  /**
   * The case the artboards do not draw and a printable profile really can be in.
   *
   * <p>A pre-AD-022 manually completed profile is {@code submitted} — therefore printable, since
   * {@code PrintedFormService.PRINTABLE_STATUSES} admits it — with no identity cycle at all, so no
   * scan and no face result. The artboards set «ناجح» flat; printing that here would assert a
   * liveness pass on a customer who never reached stage 10.
   */
  @Test
  void aProfileThatNeverReachedLivenessSaysSoRatherThanClaimingAPass() {
    List<PrintedChip> chips =
        PrintedFormAssembler.assemble(sources(manualProfile())).verificationChips();

    assertThat(chips)
        .extracting(PrintedChip::text)
        .containsExactly("التحقق الحي — لم يتم بعد", "تحقق MRZ — لم يتم بعد");
    assertThat(chips).extracting(PrintedChip::tone).containsOnly(PrintedChip.Tone.NEUTRAL);
  }

  @Test
  void anMrzThatFailedVerificationSaysSo() {
    ScanResultView failed =
        new ScanResultView(
            "PASSPORT",
            null,
            "SCAN-COPY-0001",
            "P00000001",
            false,
            "SDN",
            "M",
            LocalDate.of(1990, 1, 1),
            LocalDate.of(2021, 1, 1),
            LocalDate.of(2031, 1, 1),
            "أم درمان",
            "SDN",
            "محمد أحمد الطيب عبدالله",
            "SAMPLE FULL NAME",
            null,
            "أم درمان",
            SUBMITTED);
    ProfileDetail profile =
        profile("digital", registry(), failed, customer("married", "زوجة تجريبية", "f"), List.of());

    List<PrintedChip> chips = PrintedFormAssembler.assemble(sources(profile)).verificationChips();

    assertThat(chips.get(1).text()).isEqualTo("تحقق MRZ — غير صحيح");
    assertThat(chips.get(1).tone()).isEqualTo(PrintedChip.Tone.BAD);
  }

  /**
   * «التحقق الحي» ALONE is a substring of section 2's badge, «من وثيقة الهوية والتحقق الحي». A test
   * that looked for the bare phrase would pass against the badge on a form with no chips at all, so
   * the chip assertions above all use the full text — and this one proves the trap is real.
   */
  @Test
  void theSectionTwoBadgeIsNotTheLivenessChip() {
    PrintedFormDocument document = PrintedFormAssembler.assemble(sources(withMarriedFemale()));

    assertThat(document.sections().get(1).badge()).contains("التحقق الحي");
    assertThat(document.sections().get(1).badge())
        .isNotEqualTo(document.verificationChips().get(0).text());
  }

  /**
   * The same profile carrying a CHOSEN face result, so a test can vary the match verdict
   * independently of the row's presence. Every other fixture ties the two together.
   */
  private static ProfileDetail profileWithFaceResult(ProfileDetail base, FaceResultView face) {
    return new ProfileDetail(
        base.profileId(),
        base.referenceNumber(),
        base.branchCode(),
        base.accountNumber(),
        base.status(),
        base.provenance(),
        base.submittedAt(),
        base.createdAt(),
        base.lastActivityAt(),
        base.customerData(),
        base.channels(),
        base.scanResult(),
        face,
        base.registryResult(),
        base.artifacts(),
        base.statusHistory(),
        base.salaryCertificateState(),
        base.editableFields());
  }

  /** The sources every chip test shares; only the profile varies. */
  private static PrintedFormSources sources(ProfileDetail profile) {
    return new PrintedFormSources(
        profile,
        LABELS,
        new EnumMap<>(PrintedFormImageSlot.class),
        null,
        false,
        "op-print-1",
        Set.of(),
        COUNTRY_NAMES,
        PRINTED,
        KHARTOUM);
  }

  // ---- fixtures
  // ------------------------------------------------------------------------------------

  private static PrintedFormDocument assemble(ProfileDetail profile) {
    return assemble(profile, false, null);
  }

  private static PrintedFormDocument assemble(
      ProfileDetail profile,
      boolean includeAttachments,
      PrintedFormDocument.SalaryCertificate certificate) {
    return assemble(profile, includeAttachments, certificate, Set.of(), COUNTRY_NAMES);
  }

  /**
   * @param editedFields what {@code app.profile_field_edit} holds for this profile — the «معدَّل»
   *     marker's only source since S9-03.
   * @param countryByAlpha3 the MRZ codes the service resolved, fields 4 and 48.
   */
  private static PrintedFormDocument assemble(
      ProfileDetail profile,
      boolean includeAttachments,
      PrintedFormDocument.SalaryCertificate certificate,
      Set<Integer> editedFields,
      Map<String, String> countryByAlpha3) {

    Map<PrintedFormImageSlot, byte[]> images = new EnumMap<>(PrintedFormImageSlot.class);
    images.put(PrintedFormImageSlot.DOCUMENT, bytes("document"));
    images.put(PrintedFormImageSlot.DOCUMENT_PORTRAIT, bytes("portrait"));
    images.put(PrintedFormImageSlot.LIVENESS, bytes("liveness"));
    images.put(PrintedFormImageSlot.SIGNATURE, bytes("signature"));
    // REGISTRY_PORTRAIT deliberately absent, so the absent-box rule is exercised on every call.

    return PrintedFormAssembler.assemble(
        new PrintedFormSources(
            profile,
            LABELS,
            images,
            certificate,
            includeAttachments,
            "op-print-1",
            editedFields,
            countryByAlpha3,
            PRINTED,
            KHARTOUM));
  }

  private static byte[] bytes(String marker) {
    return marker.getBytes(StandardCharsets.UTF_8);
  }

  private static PrintedFormDocument.SalaryCertificate certificate() {
    return new PrintedFormDocument.SalaryCertificate(bytes("%PDF-payslip"), "application/pdf");
  }

  private static ProfileDetail digitalProfile() {
    return profile("digital", registry(), scan(), customer("single", null, "m"), List.of());
  }

  private static ProfileDetail marriedMale() {
    return profile(
        "digital", registry(), scan(), customer("married", "زوجة تجريبية", "m"), List.of());
  }

  private static ProfileDetail withMarriedFemale() {
    RegistryResultView female =
        new RegistryResultView(
            "ok",
            "محمد",
            "أحمد",
            "الطيب",
            "عبدالله",
            "فاطمة",
            "علي",
            "حسن",
            "محمد",
            "SAMPLE FULL",
            "NAME",
            "f",
            LocalDate.of(1990, 1, 1),
            "عنوان السجل المدني القديم",
            "000-0000-0001");
    return profile("digital", female, scan(), customer("married", "زوجة تجريبية", "f"), List.of());
  }

  /**
   * S9-02 REGRESSION GUARD. BL-135 made {@code app.profile.provenance} DERIVED: {@code
   * app.derived_provenance()} reports {@code manual} the moment an operator keys ONE field, and
   * {@code PrintedFormService} loads its profile through the very query that changed. Had this
   * assembler kept branching on {@code profile.provenance()}, a single edited street would have
   * stamped «يدوي» on all ~25 customer-entered fields — asserting on a filed bank document that an
   * operator hand-entered values the customer typed on their phone.
   *
   * <p>The marker's only source is {@code app.profile_field_edit}, so a profile with no rows there
   * marks nothing however its provenance reads. This profile is what the regression looks like:
   * provenance {@code manual}, history carrying no manual completion.
   *
   * <p>Found by {@code @agent-reviewer} at S9-02, before it ever rendered. The per-field «معدَّل»
   * marker the design asks for is S9-03's, reading V0073's {@code app.profile_field_edit}.
   */
  @Test
  void aProfileMadeManualByAFieldEditMarksNothing() {
    ProfileDetail editedNotManuallyCompleted =
        profile(
            "manual",
            registry(),
            scan(),
            customer("married", "زوجة تجريبية", "f"),
            List.of(
                new StatusHistoryEntryView(
                    1,
                    "in_progress",
                    "submitted",
                    SUBMITTED,
                    "customer",
                    null,
                    null,
                    null,
                    null,
                    null,
                    false)));

    assertThat(fields(assemble(editedNotManuallyCompleted)))
        .as("a derived-manual profile is not a manually COMPLETED one")
        .noneMatch(PrintedField::edited);
  }

  /**
   * A manually completed profile: {@code submitted}, {@code manual}, and — per {@code operator.md}
   * — no scan, no face match and no Civil Registry lookup, which is why both results are null here
   * rather than merely sparse.
   */
  private static ProfileDetail manualProfile() {
    return profile(
        "manual",
        null,
        null,
        customer("single", null, "m"),
        List.of(
            new StatusHistoryEntryView(
                1,
                "in_progress",
                "submitted",
                SUBMITTED,
                "operator",
                "op-manual-1",
                null,
                null,
                null,
                "branch visit",
                true)));
  }

  /** The digital fixture's default channel states: SMS and email verified. */
  private static ProfileDetail profile(
      String provenance,
      RegistryResultView registry,
      ScanResultView scan,
      CustomerDataView customer,
      List<StatusHistoryEntryView> history) {
    return profile(provenance, registry, scan, customer, history, VERIFIED_SMS_AND_EMAIL);
  }

  private static ProfileDetail profile(
      String provenance,
      RegistryResultView registry,
      ScanResultView scan,
      CustomerDataView customer,
      List<StatusHistoryEntryView> history,
      List<ChannelStateView> channels) {

    return new ProfileDetail(
        "11111111-1111-1111-1111-111111111111",
        "SFB-000000777",
        "16",
        "0000009999",
        "submitted",
        provenance,
        SUBMITTED,
        SUBMITTED,
        SUBMITTED,
        customer,
        channels,
        scan,
        scan == null ? null : new FaceResultView(true, 5, 4, true, SUBMITTED),
        registry,
        List.of(),
        history,
        SalaryCertificateState.PRESENT,
        List.of());
  }

  private static RegistryResultView registry() {
    return new RegistryResultView(
        "ok",
        "محمد",
        "أحمد",
        "الطيب",
        "عبدالله",
        "فاطمة",
        "علي",
        "حسن",
        "محمد",
        "SAMPLE FULL",
        "NAME",
        "m",
        LocalDate.of(1990, 1, 1),
        "عنوان السجل المدني القديم",
        "000-0000-0001");
  }

  /**
   * The scan's own identity number differs from the registry's on purpose. A live adapter treats a
   * difference as {@code not_found}, so this pairing cannot occur in production — which is exactly
   * what makes it a good probe: it can only appear on the page if the assembler read the wrong one.
   */
  private static ScanResultView scan() {
    return new ScanResultView(
        "PASSPORT",
        null,
        "SCAN-COPY-0001",
        "P00000001",
        true,
        "SDN",
        "M",
        LocalDate.of(1990, 1, 1),
        LocalDate.of(2021, 1, 1),
        LocalDate.of(2031, 1, 1),
        "أم درمان",
        "SDN",
        "محمد أحمد الطيب عبدالله",
        "SAMPLE FULL NAME",
        null,
        "أم درمان",
        SUBMITTED);
  }

  private static CustomerDataView customer(String maritalStatus, String spouse, String sex) {
    return new CustomerDataView(
        "+249912345678",
        "sample.new@example.invalid",
        sex,
        maritalStatus,
        spouse,
        false,
        null,
        7,
        "25",
        1,
        76_000L,
        "passport",
        "قبيلة تجريبية",
        "SD",
        "SD",
        "KRT",
        null,
        "أم درمان",
        "SD",
        "KRT",
        "OMD",
        null,
        null,
        "أم درمان",
        null,
        null,
        null,
        null,
        "شركة تجريبية للتجارة",
        "SD",
        null,
        "OMD",
        "ولاية بنص حر",
        null,
        "المهدية",
        "الثورة",
        "8",
        null,
        List.of(new IncomeSourceView("1", true, null)));
  }

  // ---- reading the assembled document ----------------------------------------------------------

  private static List<PrintedField> fields(PrintedFormDocument document) {
    return document.sections().stream().flatMap(section -> section.fields().stream()).toList();
  }

  private static List<Integer> fieldNumbers(PrintedFormDocument document) {
    return fields(document).stream().map(PrintedField::number).sorted().toList();
  }

  private static Map<Integer, PrintedField> byNumber(PrintedFormDocument document) {
    return fields(document).stream()
        .collect(java.util.stream.Collectors.toMap(PrintedField::number, field -> field));
  }
}
