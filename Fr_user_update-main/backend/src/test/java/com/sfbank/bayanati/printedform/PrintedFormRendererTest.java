package com.sfbank.bayanati.printedform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sfbank.bayanati.printedform.config.FopFactoryProvider;
import com.sfbank.bayanati.printedform.config.RenderScopedImages;
import com.sfbank.bayanati.printedform.domain.FieldOrigin;
import com.sfbank.bayanati.printedform.domain.PrintedChip;
import com.sfbank.bayanati.printedform.domain.PrintedField;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedImage;
import com.sfbank.bayanati.printedform.domain.PrintedSection;
import com.sfbank.bayanati.printedform.domain.PrintedValue;
import com.sfbank.bayanati.printedform.service.FoDocumentWriter;
import com.sfbank.bayanati.printedform.service.PrintedFormRenderer;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.fop.apps.FopFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The form rendered end to end from a synthetic document. There is ONE form since AD-022 (S9-01);
 * this rendered both variants until that ruling collapsed them.
 *
 * <p>Like the AD-014 acceptance render, this writes its output to {@code target/printed-form/} so
 * the pages can be LOOKED AT against the artboards. The same honest limit applies: extracted text
 * proves what the document says and in what order, and cannot prove where anything sits on the page
 * or that the Arabic joined.
 */
class PrintedFormRendererTest {

  private static final Path OUT = Path.of("target", "printed-form");

  private final FoDocumentWriter writer = new FoDocumentWriter();
  private final PrintedFormRenderer renderer =
      new PrintedFormRenderer(FopFactoryProvider.create(), writer);

  @Test
  void rendersTheWholeForm() throws Exception {
    Files.createDirectories(OUT);

    PrintedFormDocument document = SyntheticForm.document();
    byte[] pdf = renderer.render(document);
    String name = "printed_form";
    Files.write(OUT.resolve(name + ".pdf"), pdf);
    Files.writeString(
        OUT.resolve(name + ".fo.xml"), writer.write(document), StandardCharsets.UTF_8);

    try (PDDocument rendered = Loader.loadPDF(pdf)) {
      PDFRenderer rasteriser = new PDFRenderer(rendered);
      for (int page = 0; page < rendered.getNumberOfPages(); page++) {
        BufferedImage image = rasteriser.renderImageWithDPI(page, 130);
        ImageIO.write(image, "PNG", OUT.resolve(name + "-page-" + (page + 1) + ".png").toFile());
      }

      String text = new PDFTextStripper().getText(rendered);

      // The header and the compact identity line. The internal-use notice was asserted here too
      // until S9-06 stopped printing it -- see below, and AD-022 (h).
      assertThat(text).contains(PrintedFormDocument.TITLE);
      assertThat(text).contains(SyntheticForm.NAME, SyntheticForm.REFERENCE);

      // The internal-use notice was asserted HERE and is gone with it: AD-022 (h) stops printing
      // it. Nothing text-shaped replaces it on this page, and that is not an omission — of the two
      // things the new header carries, the wordmark is an image, and the app name is Amiri text
      // that does not survive extraction (see the per-page loop below for the measurement). The
      // header is asserted there, on what it draws; that «بياناتي» is emitted at all is asserted
      // on the FO in theHeaderCarriesTheAppNameAndTheBrandUris.

      // Ticket 04 decision 5: no redaction. The value alone; field 3's LABEL is asserted in
      // theCorrectedLabelsAreOnTheForm, against the FO, because «الفرع» beside it does not
      // survive PDF text extraction.
      assertThat(text).as("unredacted account number").contains(SyntheticForm.ACCOUNT);
      // The two labels the product owner corrected on 2026-09-14 are asserted on the FO, not
      // here — see theCorrectedLabelsAreOnTheForm(). «الفرع» renders correctly but does not
      // extract: FOP emits two of its shaped glyphs with private-use codepoints (U+E000/U+E001)
      // because the subset's ToUnicode CMap has no reverse mapping for them, so reading the PDF
      // back gives something that is not the word. A render fault this is not; verified by eye on
      // target/printed-form/printed_form-page-1.png.

      // Ticket 04 decision 7: absence SAYS so.
      assertThat(text).as("absent data prints the placeholder").contains(PrintedValue.ABSENT_TEXT);

      // Ticket 04 decision 2 SUPERSEDED, 2026-09-14: one source per field, so no origin tag is
      // printed beside a value. Asserted on the ORIGIN TAG'S OWN MARKUP in the FO, not on the
      // tag strings: those legitimately appear as image captions, and not on the model either,
      // since PrintedField now REFUSES a second value and a model assertion would be empty by
      // construction. `border="0.2pt solid ` was the deleted originTag's signature and appears
      // nowhere else, so this fails the moment the tag comes back.
      assertThat(writer.write(document))
          .as("no origin tag markup anywhere in the form")
          .doesNotContain("border=\"0.2pt solid ");

      // Ticket 04 decision 3 REVERSED, 2026-09-14: no reference-list versions on the paper.
      // They remain in the print audit event's payload (ticket 09 decision 2), which is where a
      // reprint would actually be checked against.
      assertThat(text).as("no version footer").doesNotContain("إصدارات القوائم المرجعية");
      assertThat(text).as("no provenance-matrix version").doesNotContain("مصفوفة المصادر");

      // Asserted PER PAGE. The whole-document text would let «من 2» come from any page, and an
      // earlier version asserted only "صفحة 1 من", leaving M unchecked while its own description
      // claimed to prove "page N of M".
      //
      // SINCE S9-03 EVERY PAGE CARRIES THE SAME FURNITURE, and that is the property under test.
      // The two page masters collapsed into one because the approved artboards give page 2 the
      // same full header as page 1, so there is no longer a first page and a continuation page to
      // tell apart -- there is one header, and every sheet must have all of it. The old loop
      // asserted the opposite shape (a continuation marker on pages after the first and on no
      // other) and that marker no longer exists.
      int pages = rendered.getNumberOfPages();
      assertThat(pages).as("the synthetic form is long enough to continue").isGreaterThan(1);
      for (int page = 1; page <= pages; page++) {
        PDFTextStripper onePage = new PDFTextStripper();
        onePage.setStartPage(page);
        onePage.setEndPage(page);
        String pageText = onePage.getText(rendered);

        assertThat(pageText)
            .as("page %d carries «صفحة %d من %d»", page, page, pages)
            .contains("صفحة " + page + " من " + pages);

        // THE HEADER IS ON EVERY SHEET, and this assertion is the only thing that proves it.
        //
        // ANCHOR CHOICE IS THE WHOLE POINT HERE. The footer writes PRODUCT · TITLE — REFERENCE,
        // so asserting the title, the reference, the product name or the printed-by line proves
        // only that the FOOTER is present: every one of them passes with the header deleted
        // outright.
        //
        // RE-ANCHORED AT S9-06, and the anchor is no longer a string. It was «البنك السوداني
        // الفرنسي» + «التاريخ». AD-022 (h) turned the bank's name into an IMAGE, so that half
        // would have gone quietly vacuous while this test stayed green.
        //
        // NEITHER REPLACEMENT STRING WORKS, and both were tried against a real render rather than
        // reasoned about:
        //
        //   * «التاريخ» alone is not header-only, whatever the comment this replaces claimed.
        //     SyntheticForm emits it as a BODY row in «بيانات الاستمارة» -- measured, it extracts
        //     TWICE on page 1 and once on page 2 -- so on page 1 it is satisfiable with no header
        //     at all.
        //   * «بياناتي» is header-only, but it DOES NOT EXTRACT. It is set in Amiri, and the
        //     embedded subset's ToUnicode CMap has no reverse mapping for its shaped glyphs, so
        //     PDFBox returns ten private-use codepoints (U+E000..U+E006) where the name is. The
        //     same thing «الفرع» does two assertions above. The text is on the page and renders
        //     correctly; it simply cannot be read back.
        //
        // So the anchor is what the header DRAWS. Both brand marks are placed on every page at
        // sizes nothing else on the form uses -- the square roundel at 9x9mm and the wordmark at
        // 19x4mm, against the customer tiles' 40x29, 24x30, 30x30 and the signature's 40x15 --
        // and read out of each page's own content stream, which is immune to the extraction
        // problem entirely.
        //
        // IT CAN STILL FAIL, which is the point: delete the header and nothing is drawn; compact
        // it to the roundel alone and 19x4 goes; resize either mark and the numbers move. The
        // «التاريخ» assertion stays alongside as the cheap text half -- weak on page 1 for the
        // reason above, exact on page 2.
        assertThat(pageText)
            .as("page %d carries the header's date label", page)
            .contains(PrintedFormDocument.DATE_LABEL.trim());
        assertThat(imageDraws(rendered.getPage(page - 1)))
            .as("page %d draws the full header: the roundel AND the wordmark", page)
            .contains("9x9", "19x4");

        // THE REFERENCE MUST BE ON EVERY SHEET, and this is the assertion standing between the
        // form and losing it altogether. It used to be printed in exactly two places -- the
        // identity band and the continuation header -- and S9-03 deleted both. A filed bank
        // document that cannot be matched back to its profile is the failure this form exists to
        // avoid, so the header and the footer now carry it independently. Counting is what makes
        // "independently" real: one occurrence would mean one of the two had quietly stopped.
        assertThat(countOccurrences(pageText, SyntheticForm.REFERENCE))
            .as(
                "page %d carries the reference TWICE, once in the header and once in the footer",
                page)
            .isEqualTo(2);

        // AD-022 ruling 2's whole justification: the approved form always names the operator who
        // printed it, and the ruling removed the ATTRIBUTED/UNATTRIBUTED choice on that basis.
        // Until S9-03 the string existed only in the artboards.
        //
        // LABEL AND NAME ASSERTED SEPARATELY, and the label WITHOUT ITS COLON. They were one
        // concatenated string until AD-022 (n) made the name a USERNAME, and that string can no
        // longer match: with a Latin name in an LTR bidi override, bidi reordering moves the
        // label's trailing colon away from its words, so page 1 extracts as
        // «… AZ Omni eKYC faheem.operator :صفحة 1 من 2 طبع بواسطة الموظف».
        //
        // The label's WORDS do survive on every page -- measured, not assumed. An earlier version
        // of this comment claimed the whole label was unextractable and dropped the per-page
        // assertion for one against the FO; that was wrong, and it was also weaker than the FO
        // assertion already at theFooterIsThreeCellsSoTheOperatorNameIsTrulyCentred. A label FOP
        // stopped DRAWING would have passed both.
        assertThat(pageText)
            .as("page %d carries the printed-by label in its footer", page)
            .contains(PRINTED_BY_LABEL_WORDS);
        assertThat(pageText)
            .as("page %d names the printing operator in its footer", page)
            .contains(SyntheticForm.PRINTED_BY);
        assertThat(pageText)
            .as("page %d carries the product name in its footer", page)
            .contains(PrintedFormDocument.PRODUCT_NAME);
      }

      // AD-022 ruling (a): «مصدر البيانات» and its «رقمي»/«يدوي» label are OFF the paper. Ruling 1
      // deleted manual completion, so every profile is digital and a label that cannot vary tells
      // a reader nothing.
      //
      // Asserted in the negative only, which is the whole of what is left to assert. The positive
      // half of this block used to read `contains("يدوي")` and was satisfied by the identity
      // band's provenance word -- NOT, as its own comment claimed, by a marked hand-keyed field.
      // The synthetic form has no edited field at all, so the companion assertion
      // `doesNotContain("يدوي — مشغّل تجريبي")` could never have failed either.
      assertThat(text).as("no provenance label on the paper").doesNotContain("مصدر البيانات");
      assertThat(text).as("neither provenance word survives").doesNotContain("يدوي", "رقمي");
      assertThat(text)
          .as("the removed attribution banner must not reappear")
          .doesNotContain("يُذكر اسم المشغّل");
    }
  }

  @Test
  void fieldsPrintInFieldProvenanceOrderRatherThanGroupedByShape() {
    // THE DEFECT THIS EXISTS FOR, found by looking at a rendered page rather than by any assertion
    // about content: an earlier FoDocumentWriter emitted every dual-source row first and every
    // single-valued row after, which printed field 8 (mother's name) AFTER field 9 (sex). Every
    // field was present and every value correct, so nothing that checked WHAT the form says could
    // have caught it. Ticket 04 decision 1 binds the form to field-provenance.md's order.
    PrintedFormDocument document = SyntheticForm.document();
    String fo = writer.write(document);

    // ONE cursor across the whole document, moving forward only. A per-section indexOf would not
    // work and the reason is worth stating: six labels repeat across sections — «البلد» is field
    // 22, field 28 AND field 35, and «الولاية», «المدينة», «المنطقة», «الشارع» and «المربع» each
    // appear two or three times — so a search from the start of the string finds the wrong one. A
    // forward-only scan is what actually expresses "these labels are emitted in this order".
    int cursor = 0;
    for (var section : document.sections()) {
      for (var field : section.fields()) {
        int at = fo.indexOf(">" + field.label() + "<", cursor);
        assertThat(at)
            .as(
                "field %d («%s») of section «%s» is emitted, and after everything before it",
                field.number(), field.label(), section.title())
            .isGreaterThanOrEqualTo(0);
        cursor = at + 1;
      }
    }
  }

  @Test
  void latinValuesAreWrappedInAnLtrEmbeddingAndNeverInIsolates() {
    String fo = writer.write(SyntheticForm.document());

    // The mechanism the acceptance render settled on. `embed`, not `bidi-override`: an Arabic word
    // reaching a Latin-tagged field renders correctly under embed and reversed under override.
    assertThat(fo).contains("unicode-bidi=\"embed\"");
    assertThat(fo).doesNotContain("unicode-bidi=\"bidi-override\"");

    // Research R-5, confirmed live: FOP's BidiConstants has no isolate classes, so these have no
    // isolating effect and raise a missing-glyph event each. They must never reach the FO.
    assertThat(fo).as("no FSI").doesNotContain("⁨");
    assertThat(fo).as("no PDI").doesNotContain("⁩");
    assertThat(fo).as("no LRE").doesNotContain("‪");
    assertThat(fo).as("no LRM").doesNotContain("‎");
  }

  @Test
  void customerSuppliedMarkupIsEscapedRatherThanEmitted() {
    // Research R-2: every field on this form is customer- or scan-supplied. This is the value a
    // name field would carry if someone tried to inject markup through the Uqudo scan or the
    // registry, and the FO must come out as text.
    String hostile = "<fo:block>&amp;</fo:block>";

    // The hostile string goes through the LABEL and VALUE paths as well as the header ones. An
    // earlier version passed empty section and image lists, so it exercised only customerName and
    // printedBy - and a concatenation reintroduced in valueCell, which is the path this class's
    // javadoc is actually about ("every field on this form is customer- or scan-supplied"), would
    // have shipped green.
    PrintedFormDocument document =
        new PrintedFormDocument(
            hostile,
            "SFB-000000001",
            "01/01/2026 00:00",
            hostile,
            "01/01/2026 00:00",
            "01/01/2026",
            java.util.List.of(PrintedChip.neutral(hostile)),
            java.util.List.of(
                PrintedSection.twoColumn(
                    hostile,
                    java.util.List.of(
                        PrintedField.single(
                            1, hostile, PrintedValue.arabic(hostile, FieldOrigin.CUSTOMER)),
                        PrintedField.single(
                                2, hostile, PrintedValue.latin(hostile, FieldOrigin.CIVIL_REGISTRY))
                            .markedEdited(hostile)))),
            java.util.List.of(PrintedImage.signature(hostile, null)),
            false,
            null);

    String fo = writer.write(document);

    assertThat(fo).as("the injected element is not emitted as markup").doesNotContain(hostile);
    assertThat(fo).as("it is escaped instead").contains("&lt;fo:block&gt;");
    // Escaped once, not twice: &amp; must survive as the ampersand the customer typed.
    assertThat(fo).as("the ampersand is escaped exactly once").contains("&amp;amp;");

    // And the document still renders: an escaping failure that produced malformed FO would throw
    // here rather than silently print something wrong.
    assertThat(renderer.render(document)).isNotEmpty();
  }

  @Test
  void oneCustomersPortraitNeverPrintsOnTheNextCustomersForm() throws Exception {
    // THE BLOCKER THIS EXISTS FOR, found at review and reproduced before it was fixed.
    //
    // FOP's ImageCache lives on the FopFactory - a long-lived singleton by design, because building
    // one parses font metrics for every embedded face - and it keys the decoded image on the URI
    // STRING. The first version of this renderer used a URI that was constant per image slot
    // ("render-image:portrait-uqudo"), so the SECOND render through the same factory was a cache
    // hit and the resolver was never called. Customer A's face printed on customer B's form.
    //
    // The thread-scoped registry did not and could not catch it: the bytes escaped one layer above
    // the ThreadLocal, which was being cleared correctly the whole time. A per-render nonce in the
    // URI is what closes it.
    //
    // TWO renders through ONE renderer is the whole point of this test. Rendering twice from the
    // same document, or once per test method, both pass against the bug.
    byte[] first = renderer.render(documentWithPortrait(colour(0xFF, 0x00, 0x00)));
    byte[] second = renderer.render(documentWithPortrait(colour(0x00, 0xFF, 0x00)));

    assertThat(embeddedImageColour(first))
        .as("the first form carries the first customer's portrait")
        .isEqualTo(0xFF0000);
    assertThat(embeddedImageColour(second))
        .as("the second form carries the SECOND customer's portrait, not the first's")
        .isEqualTo(0x00FF00);
  }

  /** A one-image document, so the embedded image is unambiguous. */
  private static PrintedFormDocument documentWithPortrait(byte[] portrait) {
    return new PrintedFormDocument(
        "اسم تجريبي",
        "SFB-000000001",
        "01/01/2026 00:00",
        "مشغّل تجريبي",
        "01/01/2026 00:00",
        "01/01/2026",
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(PrintedImage.portrait("الصورة الشخصية", FieldOrigin.PASSPORT, portrait)),
        false,
        null);
  }

  /** Distinct from the logo's 192x192, which is the other image on the page. */
  private static final int SWATCH_EDGE = 40;

  /** A flat PNG swatch of one colour. Synthetic; nobody's face. */
  private static byte[] colour(int red, int green, int blue) throws Exception {
    BufferedImage image = new BufferedImage(SWATCH_EDGE, SWATCH_EDGE, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    graphics.setColor(new java.awt.Color(red, green, blue));
    graphics.fillRect(0, 0, SWATCH_EDGE, SWATCH_EDGE);
    graphics.dispose();
    java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
    ImageIO.write(image, "PNG", png);
    return png.toByteArray();
  }

  /**
   * The RGB of the PORTRAIT embedded in a rendered PDF, selected by its size.
   *
   * <p>Selecting "the first XObject" does not work and the reason is worth keeping: the bank's logo
   * is an image too, it sits in the page-one header of every form, and it came back first. The
   * portrait is the only {@value #SWATCH_EDGE}x{@value #SWATCH_EDGE} image on the page.
   */
  private static int embeddedImageColour(byte[] pdf) throws Exception {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      var resources = document.getPage(0).getResources();
      for (var name : resources.getXObjectNames()) {
        if (resources.getXObject(name)
            instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject embedded) {
          BufferedImage image = embedded.getImage();
          if (image.getWidth() == SWATCH_EDGE && image.getHeight() == SWATCH_EDGE) {
            return image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 0xFFFFFF;
          }
        }
      }
    }
    throw new AssertionError("the rendered form embedded no portrait at all");
  }

  @Test
  void aRenderNeverLeavesOneCustomersImagesBoundToTheThread() {
    renderer.render(SyntheticForm.document());

    // The failure this guards is specific and nasty: a ThreadLocal on a pooled request thread
    // outlives the request, so a leak would leave one customer's portrait reachable while the next
    // customer's form renders on that same thread.
    assertThat(RenderScopedImages.lookUp(FoDocumentWriter.keyFor(0)))
        .as("images are unbound after the render")
        .isNull();
  }

  @Test
  void aBlankValueIsRefusedRatherThanPrintedAsAnEmptyCell() {
    // Ticket 04 decision 7's reasoning, enforced at the type rather than left to each caller: a
    // blank cell beside a customer value reads as "the registry agreed".
    assertThatThrownBy(() -> PrintedValue.arabic("   ", FieldOrigin.CIVIL_REGISTRY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(PrintedValue.ABSENT_TEXT);

    // And an absence can never be attributed to a source. «غير متاح — السجل المدني» says the value
    // is both unavailable and read from somewhere (product-owner correction, 2026-09-14). Enforced
    // at construction so no caller can spell it, rather than left to the renderer to remember.
    assertThatThrownBy(
            () ->
                new PrintedValue(
                    PrintedValue.ABSENT_TEXT,
                    java.util.Optional.of(FieldOrigin.CIVIL_REGISTRY),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("carries no origin");
  }

  @Test
  void theFormNamesDocumentsRatherThanTheToolThatScannedThem() {
    // Product-owner correction, 2026-09-14: Uqudo is a TOOL, not a source. What a branch officer
    // needs from a filed form is which document a value was read from - a passport or a national ID
    // card - because that is what they could ask the customer to produce again. A vendor's name
    // tells them nothing and puts a supplier's brand on the bank's filing document.
    String fo = writer.write(SyntheticForm.document());
    assertThat(fo).as("no vendor name anywhere in the form").doesNotContainIgnoringCase("uqudo");
    for (FieldOrigin origin : FieldOrigin.values()) {
      assertThat(origin.tag())
          .as("the %s tag names a document or a person, not a tool", origin)
          .doesNotContainIgnoringCase("uqudo");
    }
    assertThat(FieldOrigin.ofScannedDocument("PASSPORT").tag()).isEqualTo("جواز سفر");
    assertThat(FieldOrigin.ofScannedDocument("SDN_ID").tag()).isEqualTo("بطاقة قومية");
  }

  @Test
  void theCorrectedLabelsAreOnTheForm() {
    // Product-owner corrections, 2026-09-14: «الفرع» not «المصرف», «رقم الحساب البنكي» not «رقم
    // العميل». Both are the vocabulary the rest of the system already uses — the mobile
    // account-entry screen and the back office's profile page both say «الفرع» — so the printed
    // form was the outlier rather than the standard.
    String fo = writer.write(SyntheticForm.document());

    assertThat(fo).as("field 2").contains("الفرع");
    assertThat(fo).as("field 3").contains("رقم الحساب البنكي");
    assertThat(fo).as("the old field 2 label is gone").doesNotContain("المصرف");
    assertThat(fo).as("the old field 3 label is gone").doesNotContain("رقم العميل");
  }

  @Test
  void theFiveImagesAreOnTheFormAndTheCertificateIsNot() {
    // Product-owner ruling, 2026-09-14: the identity document's front page, the portrait off it,
    // the Civil Registry photograph, the liveness frame and the signature. Captions are the back
    // office's own, so an operator reads the same words on screen and on paper - this restates
    // them rather than proving agreement, since artifactTiles.ts says outright that the Java/TS
    // mirror has no drift gate in either direction.
    String fo = writer.write(SyntheticForm.document());

    assertThat(fo)
        .as("the five images")
        .contains("وثيقة الهوية", "صورة الوثيقة", "السجل المدني", "إثبات الحياة", "التوقيع");

    // The negative half: the certificate is an ATTACHMENT, never one of the form's images. An
    // earlier version asserted `doesNotContain` over the back office's certificate caption, which
    // no production path can put in the FO and which the fixture spells differently anyway - it
    // could not fail. This asserts the model instead.
    PrintedFormDocument document = SyntheticForm.document();
    assertThat(document.images()).as("five images, no more").hasSize(5);
    assertThat(document.images())
        .as("the certificate is not among them")
        .noneMatch(image -> image.caption().equals(FoDocumentWriter.CERTIFICATE_CAPTION));
  }

  @Test
  void aFieldCannotCarryTwoValues() {
    // The invariant that replaced PrintedField.dual. BACKLOG BL-145 cites it as what makes the old
    // "a source that cannot supply a field contributes nothing" rule moot, so it needs a test.
    assertThatThrownBy(
            () ->
                new PrintedField(
                    4,
                    "الجنسية",
                    java.util.List.of(
                        PrintedValue.arabic("السودان", FieldOrigin.PASSPORT),
                        PrintedValue.arabic("السودان", FieldOrigin.CUSTOMER)),
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXACTLY ONE");
  }

  @Test
  void anAppendedPdfCertificateStillCarriesItsOwnContent() throws Exception {
    // THE BLOCKER THIS EXISTS FOR, found at review and reproduced before it was fixed.
    //
    // PDDocument.importPage copies a page SHALLOWLY: the content stream comes across, resources the
    // page INHERITS from its /Pages node do not. That is legal PDF and several generators emit it,
    // and the result is an appended certificate that renders WHITE - PDFBox even logs "inherited
    // resources of source document are not imported to destination page" and then "Missing
    // XObject". The operator holds a blank sheet where the payslip should be.
    //
    // The fixture's PDF certificate is built with its resources on the page TREE for exactly this
    // reason; a blank page would pass against the bug. This asserts the LAST page has ink on it.
    byte[] bundle = renderer.render(SyntheticForm.withCertificate(SyntheticForm.certificate()));

    try (PDDocument rendered = Loader.loadPDF(bundle)) {
      int last = rendered.getNumberOfPages() - 1;
      BufferedImage page = new PDFRenderer(rendered).renderImageWithDPI(last, 72);

      boolean anyInk = false;
      for (int x = 0; x < page.getWidth() && !anyInk; x++) {
        for (int y = 0; y < page.getHeight(); y++) {
          if ((page.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
            anyInk = true;
            break;
          }
        }
      }
      assertThat(anyInk)
          .as("the appended certificate page is not blank - its inherited resources came across")
          .isTrue();
    }
  }

  @Test
  void anImageSalaryCertificateGetsAnAttachmentPageOfItsOwn() throws Exception {
    // THE DEFECT THIS EXISTS FOR: a photographed payslip is the commonest certificate, and an
    // earlier version had no path for it at all. The javadoc claimed an image certificate was "a
    // normal attachment page written by FOP with the rest" while nothing wrote one, so an operator
    // who asked for the attachments silently did not get it. Only the PDF path existed.
    PrintedFormDocument withImageCertificate =
        SyntheticForm.withCertificate(SyntheticForm.imageCertificate());

    String fo = writer.write(withImageCertificate, "nonce");

    assertThat(fo)
        .as("the certificate's own attachment page")
        .contains(FoDocumentWriter.CERTIFICATE_CAPTION);
    assertThat(fo)
        .as("bound under its own key, outside the positional image range")
        .contains(FoDocumentWriter.CERTIFICATE_IMAGE_KEY);

    // «and it renders» was the whole of this test's positive half, and it could not fail: deleting
    // the branch in PrintedFormRenderer.imagesOf that BINDS the certificate's bytes left every test
    // green while the operator got a blank sheet captioned «شهادة المرتب» - the same silent failure
    // one layer down. The bytes have to be found in the output.
    assertThat(embeddedImageSizes(renderer.render(withImageCertificate)))
        .as("the certificate's own pixels are on the page, not just its caption")
        .contains(
            SyntheticForm.CERTIFICATE_IMAGE_WIDTH + "x" + SyntheticForm.CERTIFICATE_IMAGE_HEIGHT);
  }

  /** Every embedded image in a PDF, as {@code WIDTHxHEIGHT}, across all pages. */
  private static List<String> embeddedImageSizes(byte[] pdf) throws Exception {
    List<String> sizes = new java.util.ArrayList<>();
    try (PDDocument document = Loader.loadPDF(pdf)) {
      for (var page : document.getPages()) {
        var resources = page.getResources();
        for (var name : resources.getXObjectNames()) {
          if (resources.getXObject(name)
              instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image) {
            sizes.add(image.getWidth() + "x" + image.getHeight());
          }
        }
      }
    }
    return sizes;
  }

  @Test
  void noImageCarriesAnOriginTagBesideItsCaption() {
    // With five distinct captions an origin label stopped distinguishing and started repeating or
    // misattributing: «السجل المدني — السجل المدني», and «إثبات الحياة — جواز سفر» for a selfie.
    // The caption is followed by the end of its block, never by " — ".
    String fo = writer.write(SyntheticForm.document());

    for (String caption : SyntheticForm.IMAGE_CAPTIONS) {
      assertThat(fo)
          .as("«%s» is not followed by an origin label", caption)
          .doesNotContain(caption + " — ");
    }
  }

  @Test
  void attachmentsPrintOnlyWhenAskedForAndOnlyForImagesThatExist() throws Exception {
    // One question at print time, defaulting to no (ticket 05 decision 9, widened 2026-09-14 from
    // the salary certificate to the whole bundle).
    PrintedFormDocument withoutAttachments = SyntheticForm.document();
    PrintedFormDocument withAttachments = SyntheticForm.document(true);

    assertThat(writer.write(withoutAttachments))
        .as("no attachment pages when the operator said no")
        .doesNotContain("break-before=\"page\"");

    // Written out so the attachment pages can be LOOKED AT, like the form itself. The eyes-on
    // check is what caught the field-ordering defect and the caption duplication; a page count
    // proves neither.
    Files.createDirectories(OUT);
    byte[] bundle = renderer.render(withAttachments);
    Files.write(OUT.resolve("with-attachments.pdf"), bundle);
    try (PDDocument rendered = Loader.loadPDF(bundle)) {
      PDFRenderer rasteriser = new PDFRenderer(rendered);
      for (int page = 0; page < rendered.getNumberOfPages(); page++) {
        ImageIO.write(
            rasteriser.renderImageWithDPI(page, 110),
            "PNG",
            OUT.resolve("with-attachments-page-" + (page + 1) + ".png").toFile());
      }
    }

    String fo = writer.write(withAttachments, "nonce");
    // FOUR, not five: the fixture's registry portrait is absent, and an absent image gets no page.
    // Otherwise a manually completed profile - which has no artifacts at all by design - would
    // print five sheets saying nothing.
    assertThat(countOccurrences(fo, "break-before=\"page\""))
        .as("one attachment page per PRESENT image")
        .isEqualTo(4);

    // And the whole thing still renders, with the PDF certificate appended as its own page.
    int formPages = pageCount(renderer.render(withoutAttachments));
    int bundlePages = pageCount(renderer.render(withAttachments));
    assertThat(bundlePages)
        .as("attachments and the certificate add pages to the SAME document, not a second file")
        .isGreaterThan(formPages + 4);
  }

  /**
   * Every image DRAW on this page, as {@code WIDTHxHEIGHT} in whole millimetres.
   *
   * <p>Read from the page's own content stream, not from its resources: FOP emits ONE {@code
   * /Resources} dictionary for the whole document and points every page at it, so asking a page
   * which images it has returns the document's union and cannot tell page 1 from page 2. The {@code
   * cm} / {@code Do} pair is the draw itself. Same technique, and the same regex, as {@link
   * BrandAssetRenderTest} — which measures the marks in isolation, where this measures them on the
   * real form.
   */
  private static List<String> imageDraws(PDPage page) throws Exception {
    String content;
    try (var in = page.getContents()) {
      content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
    List<String> drawn = new java.util.ArrayList<>();
    Matcher matcher = IMAGE_PLACEMENT.matcher(content);
    while (matcher.find()) {
      double width = Math.abs(Double.parseDouble(matcher.group(1))) / 72 * 25.4;
      double height = Math.abs(Double.parseDouble(matcher.group(4))) / 72 * 25.4;
      // Locale.ROOT, and this one is LOAD-BEARING. These strings are what the per-page header
      // guard matches ("9x9", "19x4") -- no string in that header survives text extraction, so
      // image draw sizes are the only evidence page 2 carries the full header. Under a JVM
      // defaulting to an Arabic-Indic numbering locale the literals would stop matching and the
      // guard would fail for a reason that has nothing to do with the header.
      drawn.add(String.format(Locale.ROOT, "%.0fx%.0f", width, height));
    }
    return drawn;
  }

  /**
   * The brand images the header loads, named ONCE so two tests cannot disagree about them.
   *
   * <p>{@code noCustomerImageIsLeftInTheSharedFactorysCacheAfterARender} asserts nothing is left in
   * FOP's cache for these, which passes trivially for a URI the form never loads; {@code
   * theHeaderCarriesTheAppNameAndTheBrandUris} asserts the form really loads them. The pairing only
   * works while both mean the same list -- editing one list and not the other is exactly how the
   * cache guard went vacuous at S9-06, when the form stopped drawing az-lockup.png.
   */
  private static final List<String> HEADER_BRAND_URIS =
      List.of("classpath:brand/sfb-logo-circle.png", "classpath:brand/sfb-wordmark-ar-navy.png");

  /** Six numbers, the literal {@code cm}, then a named XObject draw. */
  private static final Pattern IMAGE_PLACEMENT =
      Pattern.compile(
          "([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)"
              + "\\s+cm\\s*/([A-Za-z0-9]+)\\s+Do");

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + 1);
    }
    return count;
  }

  private static int pageCount(byte[] pdf) throws Exception {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return document.getNumberOfPages();
    }
  }

  /**
   * How many LINES the footer occupies on one page, measured out of the laid-out PDF.
   *
   * <p>BL-163 was half a wrapping defect: the footer's middle third is 30% of the width, and a
   * 36-character UUID overflowed it onto a second line. "Contains the right string" cannot see
   * that, so this counts distinct text baselines instead — the same technique as {@link
   * #captionBaselines}, which is the only other place in this tree that reads {@code getYDirAdj},
   * generalised from "do these three captions share a baseline" to "how many baselines are there".
   *
   * <p><strong>Anchored on «AZ Omni eKYC», and that is not an arbitrary choice.</strong> It is the
   * first thing on the footer's first line and it is drawn in exactly one place in the whole form
   * ({@code FoDocumentWriter}'s footer start cell), so it is a unique and reliable top edge —
   * everything at or below it on the page is footer. It is also Latin, which matters: the Arabic
   * around it is reordered by bidi, so «طبع بواسطة الموظف» extracts without its colon and could not
   * anchor a position. A missing anchor fails loudly on {@code isNotNull} rather than silently
   * returning a count of zero.
   *
   * <p>No FOP area tree is involved: nothing in this backend renders one, and every existing
   * measurement here goes through PDFBox. This is the same road.
   */
  private static int footerLineCount(byte[] pdf, int pageNumber) throws Exception {
    List<Float> baselines = new java.util.ArrayList<>();
    Float[] footerTop = {null};
    try (PDDocument document = Loader.loadPDF(pdf)) {
      PDFTextStripper stripper =
          new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> at) {
              if (at.isEmpty()) {
                return;
              }
              float y = at.get(0).getYDirAdj();
              baselines.add(y);
              if (text.contains(PrintedFormDocument.PRODUCT_NAME)
                  && (footerTop[0] == null || y < footerTop[0])) {
                footerTop[0] = y;
              }
            }
          };
      stripper.setSortByPosition(true);
      stripper.setStartPage(pageNumber);
      stripper.setEndPage(pageNumber);
      stripper.getText(document);
    }
    assertThat(footerTop[0])
        .as("page %d draws «%s», the footer's Latin anchor", pageNumber, PRODUCT_NAME)
        .isNotNull();

    // Cluster what sits at or below that anchor into lines. One run per line is the passing shape;
    // a wrapped cell puts a second cluster below it.
    List<Float> lines = new java.util.ArrayList<>();
    for (Float y : baselines) {
      if (y < footerTop[0] - BASELINE_TOLERANCE) {
        continue;
      }
      if (lines.stream().noneMatch(seen -> Math.abs(seen - y) <= BASELINE_TOLERANCE)) {
        lines.add(y);
      }
    }
    return lines.size();
  }

  /** Two runs on the same visual line differ by well under a point; a wrap is several. */
  private static final float BASELINE_TOLERANCE = 2.0f;

  /**
   * «طبع بواسطة الموظف» — the footer's label WITHOUT the colon {@code PRINTED_BY_LABEL} carries.
   *
   * <p>Derived rather than retyped, so it cannot drift from the constant. The colon is dropped
   * because bidi reordering moves it away from the words when the value beside it is Latin: the
   * words extract on every page, the colon does not stay attached to them.
   */
  private static final String PRINTED_BY_LABEL_WORDS =
      PrintedFormDocument.PRINTED_BY_LABEL.substring(
          0, PrintedFormDocument.PRINTED_BY_LABEL.indexOf(':'));

  private static final String PRODUCT_NAME = PrintedFormDocument.PRODUCT_NAME;

  /**
   * BL-163, closed by AD-022 (n). The footer names the operator and FITS ON ONE LINE.
   *
   * <p>Two assertions of different kinds, deliberately. The name is a DIRECT assertion — it cannot
   * pass against a footer carrying a UUID, so it needs no revert to prove it guards anything. The
   * line count is INDIRECT, which is why the instrument that produces it is itself checked by
   * {@link #aThirtySixCharacterIdentifierStillWrapsTheFooterOntoTwoLines}: a helper that silently
   * returned 1 for everything would pass this test and prove nothing at all.
   *
   * <p>Asserted on EVERY page, because the footer is drawn by one static-content region and a
   * regression would not choose a page.
   */
  @Test
  void theFooterNamesTheOperatorOnOneLineOnEveryPage() throws Exception {
    byte[] pdf = renderer.render(SyntheticForm.document());

    try (PDDocument rendered = Loader.loadPDF(pdf)) {
      assertThat(new PDFTextStripper().getText(rendered))
          .as("the footer names the operator by username -- AD-022 (n)")
          .contains(SyntheticForm.PRINTED_BY);
    }
    assertThat(pageCount(pdf)).as("the approved design is two pages -- BL-162").isEqualTo(2);
    for (int page = 1; page <= 2; page++) {
      assertThat(footerLineCount(pdf, page))
          .as("page %d's footer is ONE line, which the artboards draw and BL-163 broke", page)
          .isEqualTo(1);
    }
  }

  /**
   * THE INSTRUMENT CHECK for {@link #footerLineCount}, and the reproduction of BL-163 itself.
   *
   * <p>A 36-character UUID is what {@code PrintedFormService} put in this cell until AD-022 (n) —
   * the footer's middle third is 30% of the page width and cannot hold it, so it wrapped and the
   * footer became two lines. Asserting that the measurement still SEES that wrap is what makes the
   * one-line assertion above worth anything: without this, a broken helper and a correct footer are
   * indistinguishable.
   *
   * <p>This is also the closest thing to a regression test for the defect that can exist at this
   * layer, since the renderer is handed a string and has no opinion about where it came from.
   */
  @Test
  void aThirtySixCharacterIdentifierStillWrapsTheFooterOntoTwoLines() throws Exception {
    PrintedFormDocument base = SyntheticForm.document();
    PrintedFormDocument withUuid =
        SyntheticForm.printedBy(base, "3f8c2a91-5b7e-4d06-9a13-c4e8f27b0d55");

    byte[] pdf = renderer.render(withUuid);

    assertThat(footerLineCount(pdf, 1))
        .as("a UUID overflows the footer's middle third and wraps -- this IS BL-163")
        .isEqualTo(2);
  }

  @Test
  void theFooterCountsTheAppendedCertificateAndSaysWhichSheetItIs() throws Exception {
    // Product-owner ruling, 2026-09-14: the certificate keeps its bare sheet, because it is the
    // customer's own document and the bank does not write on it. The BANK'S pages carry the honest
    // total and a note naming the sheet the payslip lands on.
    //
    // THE DEFECT THIS EXISTS FOR, measured on the seven-sheet bundle: «من M» was FOP's own
    // page-number-citation-last, which counts only the sheets FOP laid out. Every numbered page
    // said «من 6» on a file with seven sheets, and the seventh returned zero text runs, so nothing
    // anywhere in the document acknowledged it.
    byte[] bundle = renderer.render(SyntheticForm.withCertificate(SyntheticForm.certificate()));

    try (PDDocument rendered = Loader.loadPDF(bundle)) {
      int sheets = rendered.getNumberOfPages();
      int lastNumbered = sheets - 1; // the certificate's own sheet carries no footer

      for (int page = 1; page <= lastNumbered; page++) {
        PDFTextStripper onePage = new PDFTextStripper();
        onePage.setStartPage(page);
        onePage.setEndPage(page);
        String text = onePage.getText(rendered);

        assertThat(text)
            .as("page %d counts the whole bundle, not just the sheets FOP laid out", page)
            .contains("صفحة " + page + " من " + sheets);
        assertThat(text)
            .as("page %d names the sheet the payslip is on", page)
            .contains("الصفحة " + sheets + " " + FoDocumentWriter.CERTIFICATE_CAPTION);
      }

      // The certificate's own sheet is still bare, which is the half of the ruling that says the
      // bank does not write on the customer's document.
      PDFTextStripper certificateSheet = new PDFTextStripper();
      certificateSheet.setStartPage(sheets);
      certificateSheet.setEndPage(sheets);
      assertThat(certificateSheet.getText(rendered).trim())
          .as("nothing is stamped onto the customer's own page")
          .isEmpty();
    }

    // And with nothing appended the footer goes back to FOP's citation, which is correct there and
    // needs no second pass. Asserted so the two-pass path cannot quietly become the only path.
    assertThat(writer.write(SyntheticForm.document()))
        .as("no appended certificate, so M is resolved by FOP")
        .contains("page-number-citation-last");
  }

  @Test
  void theFooterTotalIsWhateverTheBundleActuallyRunsTo() throws Exception {
    // The certificate is OPTIONAL and so is the attachment bundle, so the total is not a fixed
    // number and must not be reached by a fixed route. Four shapes a real print takes, each with a
    // different sheet count, and one invariant across all of them: THE NUMBER IN THE FOOTER IS THE
    // NUMBER OF SHEETS IN THE FILE.
    //
    // Which route computes it differs, deliberately. With nothing stapled on, FOP's own
    // page-number-citation-last is already right and there is no second pass. Only an appended PDF
    // needs the renderer to count both halves, because FOP never sees those sheets.
    record Shape(String name, PrintedFormDocument document, boolean expectsNote) {}

    List<Shape> shapes =
        List.of(
            new Shape("form alone", SyntheticForm.document(), false),
            new Shape(
                "attachments, no certificate at all",
                SyntheticForm.withCertificate(null, true),
                false),
            new Shape(
                "a certificate the operator declined",
                SyntheticForm.withCertificate(SyntheticForm.certificate(), false),
                false),
            new Shape(
                "a photographed certificate, laid out by FOP",
                SyntheticForm.withCertificate(SyntheticForm.imageCertificate(), true),
                false),
            new Shape(
                "a PDF certificate, stapled on afterwards",
                SyntheticForm.withCertificate(SyntheticForm.certificate(), true),
                true));

    // Written out beside the PDFs, like everything else this class produces, so the sheet counts
    // can be READ rather than reasoned about. Every one of them is a different number reached a
    // different way, which is the point being made.
    Files.createDirectories(OUT);
    StringBuilder measured = new StringBuilder();

    for (Shape shape : shapes) {
      byte[] pdf = renderer.render(shape.document());
      try (PDDocument rendered = Loader.loadPDF(pdf)) {
        int sheets = rendered.getNumberOfPages();
        measured
            .append(String.format("%-44s", shape.name()))
            .append(sheets)
            .append(" sheets   صفحة 1 من ")
            .append(sheets)
            .append(
                shape.expectsNote()
                    ? "   الصفحة " + sheets + " " + FoDocumentWriter.CERTIFICATE_CAPTION
                    : "   (no note)")
            .append(System.lineSeparator());

        PDFTextStripper firstPage = new PDFTextStripper();
        firstPage.setStartPage(1);
        firstPage.setEndPage(1);
        String text = firstPage.getText(rendered);

        assertThat(text)
            .as("«%s» runs to %d sheets, and page 1 says so", shape.name(), sheets)
            .contains("صفحة 1 من " + sheets);

        // The note is not decoration on every form: it appears only when a sheet exists that the
        // bank's own pages would otherwise leave unaccounted for.
        if (shape.expectsNote()) {
          assertThat(text)
              .as("«%s» names the payslip's sheet", shape.name())
              .contains("الصفحة " + sheets + " " + FoDocumentWriter.CERTIFICATE_CAPTION);
        } else {
          assertThat(text)
              .as("«%s» has no unaccounted sheet, so no note", shape.name())
              .doesNotContain(FoDocumentWriter.CERTIFICATE_CAPTION + "");
        }
      }
    }

    Files.writeString(
        OUT.resolve("footer-shapes.txt"), measured.toString(), StandardCharsets.UTF_8);
  }

  @Test
  void theCertificateNoteNamesOneSheetOrARangeOfThem() {
    // A payslip is usually one page and sometimes several; the note has to read correctly either
    // way, and the numbers have to describe sheets that exist.
    assertThat(new FoDocumentWriter.AppendedCertificate(7, 7, 7).note())
        .isEqualTo("الصفحة 7 " + FoDocumentWriter.CERTIFICATE_CAPTION);
    assertThat(new FoDocumentWriter.AppendedCertificate(9, 7, 9).note())
        .isEqualTo("الصفحات 7-9 " + FoDocumentWriter.CERTIFICATE_CAPTION);

    assertThatThrownBy(() -> new FoDocumentWriter.AppendedCertificate(6, 7, 7))
        .as("a certificate cannot start after the last sheet")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCustomersPdfContributesItsPagesAndNothingElseToTheBanksForm() throws Exception {
    // THE BLOCKER THIS EXISTS FOR, found at the second review and reproduced live.
    //
    // PDFMergerUtility.appendDocument does not copy pages. It copies the SOURCE document's
    // /OpenAction onto the destination whenever the destination has none - and FOP output never has
    // one; it clones the source's whole /Names dictionary, which is where /JavaScript and
    // /EmbeddedFiles live; and it merges the source's /Info. So a customer who uploads a payslip
    // carrying document-level JavaScript had it copied into THE BANK'S filed form: the bytes stored
    // as a printed_form artifact and streamed to the operator's browser, executing in any viewer
    // with JavaScript enabled, under the name of a bank-generated document.
    //
    // No revert-restore: every assertion below is a DIRECT one on the merged catalog, and each
    // names the exact key appendDocument copies. None can pass against the bug.
    byte[] bundle =
        renderer.render(
            SyntheticForm.withCertificate(SyntheticForm.certificateWithActiveContent()));

    try (PDDocument printed = Loader.loadPDF(bundle)) {
      var catalog = printed.getDocumentCatalog().getCOSObject();

      assertThat(catalog.getItem(org.apache.pdfbox.cos.COSName.OPEN_ACTION))
          .as("no open-action: the customer's script does not run when the bank opens its own form")
          .isNull();
      assertThat(catalog.getItem(org.apache.pdfbox.cos.COSName.NAMES))
          .as("no names tree, which is where /JavaScript and /EmbeddedFiles live")
          .isNull();
      assertThat(catalog.getItem(org.apache.pdfbox.cos.COSName.ACRO_FORM))
          .as("no acroform, which is where XFA lives")
          .isNull();
      assertThat(printed.getDocumentInformation().getTitle())
          .as("the bank's document is not titled by the customer")
          .isNotEqualTo(SyntheticForm.HOSTILE_TITLE);
      assertThat(printed.getDocumentInformation().getAuthor())
          .as("nor authored by them")
          .isNotEqualTo(SyntheticForm.HOSTILE_TITLE);

      // And the pages DID come across - the strip must not have thrown the attachment away.
      // Compared against the SAME bundle with no certificate at all, so the difference is the
      // certificate's one page and nothing else.
      assertThat(printed.getNumberOfPages())
          .as("the certificate's page is still appended after the strip")
          .isEqualTo(pageCount(renderer.render(SyntheticForm.withCertificate(null, true))) + 1);
    }
  }

  @Test
  void anImageThatWillNotDecodeFailsThePrintRatherThanPrintingAnEmptyBox() {
    // FOP reports a missing or undecodable resource as an ERROR EVENT, not an exception: the
    // resolver throws an IOException, FOP swallows it, logs "Image not found" and lays the page out
    // without the image. The render used to return a finished-looking PDF.
    //
    // What that produces: a truncated portrait prints an EMPTY bordered box rather than the «غير
    // متاح» the absence rule requires - the model believes the image is present, so the form does
    // not say it is missing - and those bytes become the print of record. Reproduced live at
    // review before this guard existed.
    PrintedFormDocument withBrokenPortrait =
        new PrintedFormDocument(
            "اسم تجريبي",
            "SFB-000000001",
            "01/01/2026 00:00",
            "مشغّل تجريبي",
            "01/01/2026 00:00",
            "01/01/2026",
            List.of(),
            List.of(),
            List.of(
                PrintedImage.portrait(
                    "الصورة الشخصية",
                    FieldOrigin.PASSPORT,
                    "not an image at all".getBytes(StandardCharsets.UTF_8))),
            false,
            null);

    assertThatThrownBy(() -> renderer.render(withBrokenPortrait))
        .as("a form FOP could not render whole is refused, not returned")
        .isInstanceOf(PrintedFormRenderer.PrintedFormRenderFailedException.class);
  }

  @Test
  void noCustomerImageIsLeftInTheSharedFactorysCacheAfterARender() throws Exception {
    // Clearing the ThreadLocal is the smaller half. FOP's ImageCache lives on the singleton
    // FopFactory and holds both the ImageInfo and the DECODED image in a SoftMapCache, so a
    // customer's portrait stays SOFTLY reachable from a process-wide object until the JVM happens
    // to need the memory - long after the print, and outside the artifact store AD-004 exists to
    // keep identity images in. A heap or core dump taken after a print would contain it. The nonce
    // that closed the cross-customer leak makes it unbounded too: every URI is unique, so entries
    // accumulate rather than overwrite.
    //
    // Asserted on the LOGO rather than on a portrait, and that is not a dodge - it is the only
    // image on the form whose URI a test can name, since a portrait's carries the render's private
    // nonce. The cache is cleared wholesale, so the logo's absence is the portrait's absence.
    //
    // RE-ANCHORED AT S9-06, and the re-anchoring is the whole reason this comment is longer than
    // it was. The URI asserted here was brand/az-lockup.png; AD-022 (h) took that image off the
    // form. An assertion that nothing decoded a URI the render never loads is VACUOUSLY TRUE - it
    // would have stayed green forever while guarding nothing, and the S8-35 leak it was written
    // for would have been unprotected with no test failing to say so. The URIs below are the ones
    // the header actually loads now, and both are asserted: a URI that stops being loaded must
    // break this test, not silently pass it.
    FopFactory factory = FopFactoryProvider.create();
    new PrintedFormRenderer(factory, writer).render(SyntheticForm.document());

    var cache = factory.getImageManager().getCache();
    for (org.apache.xmlgraphics.image.loader.ImageFlavor flavor :
        List.of(
            org.apache.xmlgraphics.image.loader.ImageFlavor.BUFFERED_IMAGE,
            org.apache.xmlgraphics.image.loader.ImageFlavor.RENDERED_IMAGE,
            org.apache.xmlgraphics.image.loader.ImageFlavor.RAW_PNG)) {
      for (String brandUri : HEADER_BRAND_URIS) {
        assertThat(cache.getImage(brandUri, flavor))
            .as("nothing decoded during the render is still held as %s (%s)", flavor, brandUri)
            .isNull();
      }
    }
  }

  @Test
  void theRenderedFormActuallyEmbedsAmiriRatherThanSubstitutingForIt() throws Exception {
    // THE ONE FAILURE THIS CHANGE HAD NO GUARD FOR. Both halves below were measured, because the
    // two ways of breaking a font are NOT equally dangerous and it would be easy to assume they
    // are:
    //
    //   * A MISSING FILE is loud. Point `embed-url` at a name that is not on the classpath and
    //     FopFactoryProvider's resolver throws, FOP wraps it, and the render fails outright with
    //     "Failed to read font file ... not on the classpath". Nothing can ship that way.
    //   * A MISSING OR MISMATCHED TRIPLET is SILENT, and this is what the test exists for. Rename
    //     the triplet so the form's font-family="Amiri" matches nothing registered and the render
    //     SUCCEEDS: FOP falls back, emits at WARNING severity, and PrintedFormRenderer refuses only
    //     ERROR and FATAL. A complete, plausible-looking PDF is produced with «بياناتي» set in a
    //     substituted face -- and the substitute has no Arabic, so on the page it is simply not
    //     there. Verified by doing it: the assertion below is what failed, not the render.
    //
    // The same shape as the S8-33 defect fop.xconf records, and the reason Amiri's Regular-only
    // registration deserves a guard of its own rather than trust.
    //
    // Nothing else catches it. The FO assertions below check what we asked for, not what FOP did
    // with it; the per-page 9x9/19x4 checks are about images; and the app name cannot be asserted
    // on the text layer at all, because Amiri's subset extracts as private-use codepoints. The
    // PDF's own font list is the only place the truth shows up.
    byte[] pdf = renderer.render(SyntheticForm.document());

    List<String> fonts = new java.util.ArrayList<>();
    try (PDDocument rendered = Loader.loadPDF(pdf)) {
      for (PDPage page : rendered.getPages()) {
        page.getResources()
            .getFontNames()
            .forEach(
                name -> {
                  try {
                    fonts.add(page.getResources().getFont(name).getName());
                  } catch (Exception e) {
                    throw new IllegalStateException("could not read an embedded font", e);
                  }
                });
      }
    }

    assertThat(fonts)
        .as("the form embeds Amiri; a substituted face means «بياناتي» printed blank")
        .anyMatch(font -> font.contains("Amiri"));
    assertThat(fonts)
        .as("and still embeds the body face, which every other span uses")
        .anyMatch(font -> font.contains("IBMPlexSansArabic"));
  }

  @Test
  void theHeaderCarriesTheAppNameAndTheBrandUris() {
    // Two jobs, both of them about things no OTHER test in this file can see.
    //
    // The URIs: the cache guard above asserts nothing is LEFT in FOP's image cache for them, and
    // that passes trivially for an image the render never touches -- which is exactly what
    // happened to the AZ lockup at S9-06. Something has to assert the URIs are ones the form
    // really loads, or the guard rots into a tautology without failing.
    //
    // The app name: it is Amiri text, and Amiri's embedded subset gives PDFBox private-use
    // codepoints instead of letters, so «بياناتي» cannot be asserted on the rendered PDF at all.
    // The FO is where it is still a string. This is what would catch it being dropped, or
    // "corrected" to a spelling the mobile splash does not use -- PrintedFormDocumentTest pins the
    // constant's codepoints, and this pins that the form actually prints it.
    String fo = writer.write(SyntheticForm.document());

    assertThat(fo)
        .as("the header loads the roundel and the wordmark, the two URIs the cache guard names")
        .contains(HEADER_BRAND_URIS.toArray(String[]::new));
    assertThat(fo)
        .as("the header prints the app name, in Amiri, at a pinned normal weight")
        .contains(PrintedFormDocument.APP_NAME_AR)
        .contains("font-family=\"Amiri\"");
  }

  @Test
  void aCertificateTheOperatorDidNotAskForIsNotPrinted() throws Exception {
    // The guard both the renderer and the FO writer carry, which no fixture could exercise: the
    // synthetic document tied the certificate to the answer, so "the profile HAS a certificate and
    // the operator said no" was unreachable. It is the privacy-relevant direction - failing it
    // prints a customer's pay document onto a bundle nobody asked to include it in.
    int withoutAnything = pageCount(renderer.render(SyntheticForm.document()));

    assertThat(
            pageCount(
                renderer.render(SyntheticForm.withCertificate(SyntheticForm.certificate(), false))))
        .as("a PDF certificate adds no page when the operator said no")
        .isEqualTo(withoutAnything);
    assertThat(
            pageCount(
                renderer.render(
                    SyntheticForm.withCertificate(SyntheticForm.imageCertificate(), false))))
        .as("and neither does a photographed one")
        .isEqualTo(withoutAnything);
  }

  @Test
  void aCertificateThatWillNotParseFailsTheWholePrintRatherThanDroppingItSilently() {
    // The guarantee PrintedFormRenderer's own comment makes and that nothing exercised: "a
    // certificate that will not parse - encrypted, truncated - must fail the WHOLE print rather
    // than quietly yield a form without it: the operator asked for the attachments and would have
    // no way to tell they were dropped."
    //
    // It was asserted by nothing. PrintedFormRenderFailedException measured 0% line coverage on the
    // 2026-09-14 gate while the package as a whole measured 95%, so the one path that decides
    // whether a broken customer upload becomes a silently incomplete bundle or a refused print had
    // never been run. A truncated PDF is not hypothetical - it is what a half-finished upload of a
    // photographed payslip looks like.
    PrintedFormDocument.SalaryCertificate truncated =
        new PrintedFormDocument.SalaryCertificate(
            "%PDF-1.7\nthis file stops half".getBytes(StandardCharsets.UTF_8),
            PrintedFormDocument.SalaryCertificate.PDF);

    assertThatThrownBy(() -> renderer.render(SyntheticForm.withCertificate(truncated)))
        .as("the print is refused, never returned without the attachment the operator asked for")
        .isInstanceOf(PrintedFormRenderer.PrintedFormRenderFailedException.class);

    // And the thread is left clean, which is the half a failed render could most easily skip: the
    // bind happens before the FO is written and the clear is in a finally, so an exception thrown
    // from the certificate merge - after the transform, not during it - still has to reach it.
    assertThat(RenderScopedImages.lookUp(FoDocumentWriter.keyFor(0)))
        .as("a FAILED render unbinds this customer's images too")
        .isNull();
  }

  @Test
  void everyImageBoxInARowIsTheSameHeightWhetherItsImageExistsOrNot() throws Exception {
    // The defect this exists for, found by measuring the rendered page. The image box was an
    // fo:block carrying height="34mm", and FOP does not honour height on an fo:block - a block's
    // extent is driven by its content. So a present image made a box about 33mm tall and the «غير
    // متاح» placeholder made one about 20mm tall, side by side in the same row, and the absent
    // cell's caption floated 39.9pt above its row-mates'.
    //
    // The fixture's third image is deliberately absent and its first two are present, so the top
    // row is exactly the mixed case. Asserted on the RENDERED caption positions rather than on the
    // markup, because the markup is what was wrong: the old version declared a height and looked
    // correct in the FO.
    byte[] pdf = renderer.render(SyntheticForm.document());

    List<Float> topRowCaptions =
        captionBaselines(
            pdf, SyntheticForm.IMAGE_CAPTIONS.subList(0, 3), SyntheticForm.IMAGE_CAPTIONS);

    assertThat(topRowCaptions)
        .as("the three captions of the first image row were all found")
        .hasSize(3);
    float highest = topRowCaptions.stream().min(Float::compare).orElseThrow();
    float lowest = topRowCaptions.stream().max(Float::compare).orElseThrow();
    assertThat(lowest - highest)
        .as(
            "the three captions of one image row sit on one baseline (was a 39.9pt step when the"
                + " absent box was shorter than its neighbours)")
        .isLessThan(2.0f);
  }

  /**
   * The y position of each wanted caption, in the order the PDF lays them out.
   *
   * <p>Matched on a distinctive PREFIX rather than on equality: a caption comes back from PDF text
   * extraction shaped, and this class's other tests already record that some Arabic strings do not
   * survive the round trip character for character. The prefix has to be long enough not to match
   * another caption, which is what {@code others} checks.
   */
  private static List<Float> captionBaselines(byte[] pdf, List<String> wanted, List<String> others)
      throws Exception {
    List<Float> found = new java.util.ArrayList<>();
    try (PDDocument document = Loader.loadPDF(pdf)) {
      PDFTextStripper stripper =
          new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> at) {
              String trimmed = text.trim();
              for (String caption : wanted) {
                if (trimmed.equals(caption)) {
                  found.add(at.get(0).getYDirAdj());
                }
              }
            }
          };
      stripper.setSortByPosition(true);
      stripper.getText(document);
    }
    assertThat(others).as("the fixture's captions are all distinct").doesNotHaveDuplicates();
    return found;
  }

  @Test
  void theMetadataItemsAreSeparatedByRealSpaceRatherThanByCollapsedBlanks() {
    // XSL-FO's white-space-collapse defaults to true, so the four literal spaces an earlier version
    // wrote between the identity band's metadata items - and the three between the continuation
    // header's name and reference - collapsed to ONE. The four items printed as one run-on line.
    // Confirmed by measurement: PDFBox returned the whole metadata line as a single text run with
    // single spaces at the separators.
    //
    // BOTH of those callers are gone since S9-03 -- the band is now a compact line and the
    // continuation header no longer exists -- so this test is deliberately re-pointed rather than
    // deleted. The compact identity line separates name, submission time and print time the same
    // way, and the verification chips separate the two chips the same way, so the trap is still
    // live and still one literal space away from returning.
    String fo = writer.write(SyntheticForm.document());

    assertThat(fo)
        .as("the separators are fixed-length leaders, which collapsing does not touch")
        .contains("leader-length=\"5mm\"");
    assertThat(fo)
        .as("and no run of literal blanks is left anywhere, since a run of them is not space")
        .doesNotContain("   ");
  }

  @Test
  void theNotCollectedIdentityDocumentsFieldIsNotOnTheForm() {
    // Field 51. Product-owner ruling, 2026-09-14: field-provenance.md marks it "Not collected", so
    // the row could only ever have reported the absence of something nobody asks for.
    PrintedFormDocument document = SyntheticForm.document();
    assertThat(document.sections().stream().flatMap(section -> section.fields().stream()))
        .as("field 51 is absent from the form")
        .noneMatch(field -> field.number() == 51);
    assertThat(writer.write(document)).doesNotContain("مستندات الهوية");
  }

  @Test
  void anEditedFieldIsMarkedEditedRatherThanManual() {
    // AD-022 deleted manual completion, so «يدوي» could no longer mean anything: the only hand
    // keying left is an operator correcting a value the CUSTOMER supplied, which is «معدَّل».
    // Nothing asserted the old word, so it would have changed silently either way.
    String fo = writer.write(SyntheticForm.document());

    assertThat(fo).as("the marker beside an edited value").contains(PrintedField.EDITED_MARKER);

    // The fixture marks TWO fields, so before S9-03's commit 2 «يدوي» appeared three times: once
    // as the identity band's provenance label and once beside each marked field. Counting told
    // those apart while the band still existed. THE BAND IS NOW GONE (AD-022 ruling (a)), so the
    // last legitimate occurrence goes with it and the count is zero — which is the stronger
    // assertion the comment above was waiting for.
    assertThat(countOccurrences(fo, "يدوي"))
        .as("the old marker and the provenance label are both off the form entirely")
        .isZero();
    assertThat(countOccurrences(fo, PrintedField.EDITED_MARKER))
        .as("both marked fields carry the new one")
        .isEqualTo(2);
    // The amber this forbids is no longer a constant anywhere — S9-03 deleted MANUAL along with
    // the identity band that was its only caller. Kept as a literal, and kept on purpose: the
    // point is that this exact fill never comes back, and a negative assertion that referenced a
    // live constant would be deleted the day someone removed the constant.
    assertThat(fo)
        .as("an outline chip, not the filled amber one, which read as a warning")
        .doesNotContain("background-color=\"#8a6a1f\"");
  }

  @Test
  void theLivenessFrameLeavesTheGridWithoutLeavingTheBundle() {
    // AD-022 / product-owner ruling 2026-09-16, and the reason PrintedImage needed an onPage flag
    // at all: the approved page 1 shows FOUR tiles and the liveness frame is not one of them,
    // while the frame is still captured, still stored, and still printed as its own appended
    // sheet. Suppressing it by dropping it from the images list would have taken the attachment
    // with it, silently, because attachments() walks the same list.
    PrintedFormDocument document = SyntheticForm.withLivenessOffTheGrid(true);
    String fo = writer.write(document, "nonce");

    // The grid is everything before the first attachment page.
    String grid = fo.substring(0, fo.indexOf("break-before=\"page\""));

    // THE INDEX IS THE KEY. The liveness frame is slot 3 of five and the signature is slot 4, so a
    // filtered grid that renumbered from zero would still draw four tiles — with the LIVENESS
    // FRAME under the signature's caption. Every tile would hold a real image and nothing would
    // fail. Asserting the surviving indices is what makes that impossible.
    // Not image-2: the fixture's registry portrait is ABSENT, so its tile prints «غير متاح» and
    // references no image at all. The keys that do appear are the present, on-page ones.
    assertThat(grid)
        .as("the surviving tiles keep their original image keys")
        .contains("image-0", "image-1", "image-4");
    assertThat(grid).as("and the liveness frame is not among them").doesNotContain("image-3");

    assertThat(grid)
        .as("four tiles in one row of four, per the approved page 1")
        .contains("column-width=\"25%\"");

    // The frame is still in the bundle. Four present images, four appended sheets: the fixture's
    // registry portrait is absent and an absent image gets no page.
    assertThat(fo.substring(grid.length()))
        .as("the liveness frame still prints as its own attachment sheet")
        .contains("image-3");
    assertThat(countOccurrences(fo, "break-before=\"page\""))
        .as("one attachment page per PRESENT image, the liveness frame included")
        .isEqualTo(4);
  }

  @Test
  void theFormDrawsNoWatermarkAndNoRegionBackgroundAtAll() {
    String fo = writer.write(SyntheticForm.document());

    // INVERTED AT S9-06 rather than deleted, because a removed guard is one nobody notices is gone.
    // It asserted the watermark WAS a fox:-scaled region background; AD-022 (j) took the watermark
    // off the page, and what needs guarding now is that nothing puts a background back without a
    // ruling to do it.
    assertThat(fo)
        .as("no watermark: the AZ asset is in the repository but the form does not reference it")
        .doesNotContain("az-watermark");
    assertThat(fo)
        .as("and no region background of any kind behind the content")
        .doesNotContain("background-image");

    // The fox: namespace went with it -- it was declared for the watermark's sizing attributes and
    // nothing else in this document ever used the extension.
    assertThat(fo)
        .as("the fox extension namespace is no longer declared, having no remaining user")
        .doesNotContain("xmlns:fox", "fox:background-image");

    // KEPT FROM THE ORIGINAL, and it is the half that still guards something. A positioned
    // container in static-content paints AFTER the body, so anything spelled that way draws ON TOP
    // of the form rather than behind it. That was the rejected spelling for the watermark and it
    // stays rejected for whatever a later session might try to put there.
    assertThat(fo)
        .as("never an absolutely positioned overlay, which would paint over the form")
        .doesNotContain("absolute-position=\"fixed\"");
  }

  @Test
  void oneMasterCarriesTheSameFullHeaderOnEveryPage() {
    String fo = writer.write(SyntheticForm.document());

    // The approved artboards give page 2 the same header as page 1, so the first/rest pair has
    // nothing left to differ in. FO allows ONE static-content per flow-name, so one master is one
    // header -- written once here and repeated by FOP on every sheet.
    assertThat(countOccurrences(fo, "<fo:simple-page-master"))
        .as("one page master, not the first/rest pair")
        .isEqualTo(1);
    assertThat(fo)
        .as("and therefore no page-position alternation at all")
        .doesNotContain("page-sequence-master", "conditional-page-master-reference");
    assertThat(countOccurrences(fo, "flow-name=\"header\""))
        .as("one header static-content")
        .isEqualTo(1);

    // The marker the second master existed to print. A page carrying the whole letterhead and
    // «صفحة 2 من 2» already says it is a continuation, twice.
    assertThat(fo).doesNotContain("تابع من الصفحة السابقة");
  }

  @Test
  void theFooterIsThreeCellsSoTheOperatorNameIsTrulyCentred() {
    String fo = writer.write(SyntheticForm.document());

    assertThat(fo)
        .as("AD-022 ruling 2's promise, which had no implementation before S9-03")
        .contains(PrintedFormDocument.PRINTED_BY_LABEL);
    assertThat(fo).contains(PrintedFormDocument.PRODUCT_NAME);

    // A LEADER CANNOT CENTRE A MIDDLE PART. The old footer justified two ends with a stretching
    // space between them, which gives a true start, a true end and no true centre -- the middle
    // would sit wherever the first part's length left it and move from page to page. Three fixed
    // cells is what makes «طبع بواسطة الموظف» land in the same place on every sheet.
    assertThat(fo).contains("text-align=\"center\"");

    // «من M» stays FOP's own citation while nothing is appended; PrintedFormRenderer swaps it for
    // a literal when PDFBox is going to staple a certificate on behind the form.
    assertThat(fo).contains("<fo:page-number-citation-last");
  }

  /**
   * The frame is ONE PER SECTION and it spans that section's SUB-HEADINGS.
   *
   * <p>Product-owner request on the rendered pages, 2026-09-16: the approved artboards rule a box
   * around each section's rows and a line between every field, and the form had neither — which the
   * product owner read as the printout being harder to follow than the design.
   *
   * <p>The grouping is the part worth guarding. The document model is a FLAT list in which {@code
   * isSubHeading} is a property of an entry, with nothing tying a sub-heading to the heading above
   * it. The approved page 2 puts a single box around the whole of section 3 — six sub-headings and
   * every row inside one rule — so a frame drawn per SECTION object would print seven boxes where
   * the design has one. Asserted on a fixture shaped exactly like that: one top-level section, two
   * sub-headings under it.
   */
  @Test
  void oneFrameWrapsASectionAndAllOfItsSubHeadings() {
    PrintedFormDocument document =
        SyntheticForm.withSections(
            List.of(
                PrintedSection.fullWidth("٣ — البيانات المُقدَّمة من العميل", List.of()),
                PrintedSection.subHeading(
                    "الحساب والفرع",
                    List.of(
                        PrintedField.single(
                            3,
                            "رقم الحساب البنكي",
                            PrintedValue.latin("0000009999", FieldOrigin.CUSTOMER)))),
                PrintedSection.subHeading(
                    "قنوات الاتصال",
                    List.of(
                        PrintedField.single(
                            25,
                            "التلفون",
                            PrintedValue.latin("+249912345678", FieldOrigin.CUSTOMER))))));
    String fo = writer.write(document);

    // border-before-width="0pt" is the frame's signature -- no other block suppresses one edge,
    // and it is suppressed because the heading band above already draws that line.
    assertThat(countOccurrences(fo, "border-before-width=\"0pt\""))
        .as("ONE frame for the section and both its sub-headings, not one per section object")
        .isEqualTo(1);

    // And both sub-headings really are inside it rather than after it.
    int frame = fo.indexOf("border-before-width=\"0pt\"");
    assertThat(fo.indexOf("الحساب والفرع")).isGreaterThan(frame);
    assertThat(fo.indexOf("قنوات الاتصال")).isGreaterThan(frame);
  }

  @Test
  void theRowRuleRunsUnderTheWholeRowRatherThanUnderTheValueAlone() {
    PrintedFormDocument document =
        SyntheticForm.withSections(
            List.of(
                PrintedSection.fullWidth(
                    "قسم",
                    List.of(
                        PrintedField.single(
                            3,
                            "رقم الحساب البنكي",
                            PrintedValue.latin("0000009999", FieldOrigin.CUSTOMER))))));
    String fo = writer.write(document);

    // TWO, one per cell. It was ONE -- the value cell only -- so on a single-field row the line
    // covered 83% of the width and stopped short of the label beside it, and on a paired row 33%
    // of each half. FO gives fo:table-row no border FOP honours, so the rule has to be carried by
    // the cells; carrying it on one of them is what made the rows read as unseparated.
    assertThat(countOccurrences(fo, "border-after-color=\"" + "#EDEDEE" + "\""))
        .as("the hairline is on the label cell as well as the value cell")
        .isEqualTo(2);
  }

  @Test
  void theImageGridStaysOnPageOneWhenASectionBreaksToPageTwo() {
    // THE DEFECT THIS EXISTS FOR. The grid was written after every section, which was invisible
    // while no section started a page. Section 3 now does, so a grid still written last would
    // follow it onto page 2 — and the approved page 1 is header, section 1, section 2, then the
    // four tiles. Asserted on ORDER within the FO, because "which page" is not a thing the FO
    // says: the break is what creates page 2, so anything written before it is on page 1.
    PrintedFormDocument document =
        SyntheticForm.withSections(
            List.of(
                PrintedSection.badged("١ — بيانات الهوية", "من السجل المدني", List.of()),
                PrintedSection.fullWidth("٣ — البيانات المُقدَّمة من العميل", List.of())
                    .startingNewPage()),
            List.of(PrintedImage.signature("التوقيع", new byte[] {1, 2, 3})));
    String fo = writer.write(document);

    // RE-ANCHORED, NOT RELAXED. This used to key on «الصور والتوقيع», the section band that stood
    // over the tiles, and S9-03 removed that band because the approved page 1 has no such heading.
    // Deleting the assertion with it would have retired the guard for the very defect commit 4 was
    // written to fix, and the test would still have passed. The tile's own image URI is the
    // durable anchor: it is what "the grid was written here" actually means.
    assertThat(fo.indexOf("image-0"))
        .as("the image grid is written before the page break, so it lands on page 1")
        .isLessThan(fo.indexOf("break-before=\"page\""));

    // The chips lead the tiles on the approved page 1, and they are on page 1 for the same reason.
    assertThat(fo.indexOf(SyntheticForm.LIVENESS_CHIP))
        .as("the verification chips precede the tiles and land on page 1 with them")
        .isLessThan(fo.indexOf("image-0"));

    assertThat(fo)
        .as("the band that used to head the grid is gone, as the artboards have none")
        .doesNotContain("الصور والتوقيع");
  }

  @Test
  void aSubHeadingWithNoFieldsIsNotWrittenAtAll() {
    // «قنوات الاتصال» carries only fields 25 and 26, and AD-022 ruling 4 removes both when no
    // channel is verified. Writing the heading anyway would print a rule and a title over empty
    // space. A SECTION heading with no fields still prints -- section 3's own heading is exactly
    // that, and it introduces the six beneath it.
    PrintedFormDocument document =
        SyntheticForm.withSections(
            List.of(
                PrintedSection.fullWidth("٣ — البيانات المُقدَّمة من العميل", List.of()),
                PrintedSection.subHeading("قنوات الاتصال", List.of()),
                PrintedSection.subHeading(
                    "الحساب والفرع",
                    List.of(
                        PrintedField.single(
                            3,
                            "رقم الحساب البنكي",
                            PrintedValue.latin("0000009999", FieldOrigin.CUSTOMER))))));
    String fo = writer.write(document);

    assertThat(fo).as("the empty sub-heading is not written").doesNotContain("قنوات الاتصال");
    assertThat(fo).as("the one with a field is").contains("الحساب والفرع");
    assertThat(fo).as("and the section heading prints with no fields of its own").contains("٣ — ");
  }

  @Test
  void aSubHeadingIsARuleAndASectionCarriesItsBadgeAtTheFarMargin() {
    PrintedFormDocument document = SyntheticForm.withApprovedHeadings();
    String fo = writer.write(document);

    assertThat(fo).as("the section's badge").contains("من السجل المدني");
    assertThat(fo)
        .as("pushed to the far margin by a leader, which only expands under text-align-last")
        .contains("text-align-last=\"justify\"");
    assertThat(fo).as("the sub-heading's own title").contains("قنوات الاتصال");
    assertThat(countOccurrences(fo, "break-before=\"page\""))
        .as("section 3 starts a fresh page, which is what makes the form two pages")
        .isEqualTo(1);
  }
}
