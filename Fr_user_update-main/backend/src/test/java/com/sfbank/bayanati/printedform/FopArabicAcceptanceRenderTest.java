package com.sfbank.bayanati.printedform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sfbank.bayanati.printedform.config.FopFactoryProvider;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.events.Event;
import org.apache.fop.events.EventFormatter;
import org.apache.fop.events.EventListener;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * AD-014's ACCEPTANCE GATE, and it is a gate rather than a test: section 8 of
 * docs/sessions/2026-09-13-research-arabic-pdf-toolchain.md requires that before any form layout
 * exists, Apache FOP is proven to shape Arabic correctly and the render is READ BY SOMEONE WHO
 * READS ARABIC. If FOP mis-shapes the face, the layout would be thrown away with the library,
 * because XSL-FO does not port to HTML.
 *
 * <p>So this class does two things a normal test does not. It writes its artifacts to {@code
 * target/acceptance-render/} — the PDF, a PNG of each page rasterised by PDFBox, the extracted text
 * and FOP's own event log — so a person can look at them; and it asserts the half of the proof a
 * machine can actually judge.
 *
 * <p><strong>What a machine can and cannot judge here, stated honestly.</strong> Extracted text
 * proves the PDF carries the ORIGINAL Arabic-block codepoints in logical order — that FOP mapped
 * glyphs through the font's GSUB tables rather than baking presentation forms into the content
 * stream, and that the bidi pass did not reverse the digits of an account number.
 *
 * <p>It cannot prove ANY of the following, and all three rest on the PNG being read by a person —
 * which is why section 8 insists on that rather than treating a green test as the gate:
 *
 * <ul>
 *   <li><b>That the letters JOIN.</b> A font that emitted four isolated forms and one that emitted
 *       a correctly joined word extract to the same string.
 *   <li><b>Which side a leading {@code +} lands on.</b> Proven by this very document: case (أ) on
 *       page 3, which the finding below marks WRONG, still extracts as {@code +249912345678}. Text
 *       extraction reports logical order; the defect is in the visual order.
 *   <li><b>Where anything sits on the page</b> — that labels went right, that the footer's page
 *       number went left.
 * </ul>
 *
 * <p><strong>Synthetic values only</strong> (CLAUDE.md): the account number, the national number,
 * the {@code +249} phone number and the {@code FRU-} reference below are invented for this file.
 * None is, or resembles, a real one.
 *
 * <p>The five section-8 checks, each mapped to what carries it:
 *
 * <ol>
 *   <li><b>Shaping</b> — page 1 sets words that between them require the initial, medial, final and
 *       isolated forms, and a lam-alef ligature.
 *   <li><b>Bidi with numbers</b> — one Arabic sentence carrying a synthetic account number, a
 *       {@code +249} E.164 number and {@code FRU-000000001} on the same line.
 *   <li><b>RTL page furniture</b> — {@code writing-mode="rl-tb"} on the page sequence, a two-column
 *       label/value table, and a footer in {@code xsl-region-after}.
 *   <li><b>Page N of M</b> — a forced three-page document via {@code break-before="page"}.
 *   <li><b>FSI/PDI negative control</b> — one line deliberately wrapping a Latin value in U+2068
 *       and U+2069, to observe what FOP does with characters its {@code BidiConstants} has no class
 *       for (research R-5).
 *   <li><b>The leading {@code +}</b> — five spellings of the same E.164 number side by side. Added
 *       during this render, and see below for why.
 * </ol>
 *
 * <h2>What this render found, and what it CORRECTS</h2>
 *
 * <p>AD-014 holds: FOP shapes IBM Plex Sans Arabic correctly. All four positional forms join, the
 * lam-alef renders as one ligature, no word broke, and the only missing glyphs in the whole
 * document are the two isolate characters the negative control deliberately fed it.
 *
 * <p><strong>But the research's section 6 mechanism for a mixed-direction value is WRONG, and page
 * 3 is the evidence.</strong> Section 6 said to give a Latin value its own {@code fo:block} with
 * {@code writing-mode="lr-tb"} and insert no control characters, on the reasoning that a block is
 * its own bidi paragraph. Rendered, that leaves {@code +249912345678} displayed as {@code
 * 249912345678+} — the leading {@code +} dragged to the far end, which is precisely the defect
 * wayfinder ticket 08 predicted would "bite again in the PRINT renderer". It is not a FOP bug: a
 * leading {@code +} is bidi class ES, it is not between two numbers, so UAX#9 resolves it as a
 * neutral and a neutral between Arabic text and the paragraph direction goes RTL.
 *
 * <p>Five spellings were rendered and read. Two are wrong and three are right:
 *
 * <ul>
 *   <li>bare, inside the Arabic sentence — WRONG, {@code +} at the far end
 *   <li>its own block with {@code writing-mode="lr-tb"} — WRONG, unchanged. This is the one the
 *       research recommended and the component card draft repeated.
 *   <li>{@code fo:bidi-override direction="ltr" unicode-bidi="bidi-override"} — correct
 *   <li>{@code fo:bidi-override direction="ltr" unicode-bidi="embed"} — correct
 *   <li>the same {@code embed} wrapper around a Latin-script NAME — correct
 * </ul>
 *
 * <p><strong>The renderer uses {@code embed}</strong>, and the choice between the two working
 * overrides is not cosmetic. {@code bidi-override} forces every character in the run to LTR
 * regardless of what it is, so an Arabic word that ever found its way into a field tagged as a
 * Latin value would render reversed, letter by letter, and still look like text. {@code embed}
 * opens an LTR embedding and lets UAX#9 resolve what is inside it, so the same field degrades to
 * correct rather than to plausible nonsense. Given that every value on this form arrives from a
 * customer or a scan, degrading safely is the whole requirement.
 *
 * <p>Both working spellings are MARKUP, not text: unlike the LRM/RLM and LRE..PDF alternatives the
 * research also offered, they insert nothing into the string an operator can select and copy out of
 * the PDF, and they raise no missing-glyph event.
 */
class FopArabicAcceptanceRenderTest {

  private static final Path OUT = Path.of("target", "acceptance-render");

  /**
   * Synthetic and invented for this file. Deliberately NOT "0000001001", which reads as invented
   * but is the first seeded stub account (application.properties) and has been live-probed on
   * staging under BL-089 - a fixture sharing a number with a real staging profile is a confusion
   * waiting to be had, even where no rule forbids it.
   */
  private static final String ACCOUNT = "0000009999";

  private static final String PHONE = "+249912345678";
  private static final String REFERENCE = "FRU-000000001";

  @Test
  void fopRendersArabicCorrectlyEnoughToBuildTheFormOn() throws Exception {
    Files.createDirectories(OUT);

    List<String> events = new ArrayList<>();
    byte[] pdf = render(acceptanceFo(), events);

    Files.write(OUT.resolve("acceptance.pdf"), pdf);
    Files.writeString(
        OUT.resolve("fop-events.txt"),
        events.isEmpty() ? "(no events)\n" : String.join("\n", events) + "\n",
        StandardCharsets.UTF_8);

    String text;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages())
          .as("section 8.4 — forced three-page document")
          .isEqualTo(3);

      PDFRenderer renderer = new PDFRenderer(document);
      for (int page = 0; page < document.getNumberOfPages(); page++) {
        BufferedImage image = renderer.renderImageWithDPI(page, 150);
        ImageIO.write(image, "PNG", OUT.resolve("page-" + (page + 1) + ".png").toFile());
      }

      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(false);
      text = stripper.getText(document);
    }
    Files.writeString(OUT.resolve("extracted.txt"), text, StandardCharsets.UTF_8);

    // Section 8.1 — the Arabic words survive as ARABIC-BLOCK codepoints (U+0600..U+06FF). Had FOP
    // pre-shaped to presentation forms (approach (a) of the research's section 2), these would
    // extract as U+FE70..U+FEFF instead, and this pair of assertions is what tells the two apart.
    // The font DOES carry 140 presentation-form cmap entries (measured with fontTools at S8-33), so
    // the legacy path was available and FOP declining to take it is a real observation.
    assertThat(text).contains(SHAPING_WORDS);
    assertThat(text.chars().filter(codepoint -> codepoint >= 0xFE70 && codepoint <= 0xFEFF).count())
        .as("section 8.1 — no Arabic presentation-form codepoints in the content stream")
        .isZero();

    // Section 8.2 — the Latin-script values keep their own order inside an RTL page. A naive bidi
    // pass reverses these, which is the failure this check exists for.
    assertThat(text).as("section 8.2 — account number in logical order").contains(ACCOUNT);
    assertThat(text)
        .as("section 8.2 — E.164 number present, digits in logical order")
        .contains(PHONE);
    assertThat(text).as("section 8.2 — reference number").contains(REFERENCE);

    // Section 8.4 — the page-number mechanism resolved rather than printing a placeholder.
    assertThat(text).as("section 8.4 — page N of M resolved").contains(PAGE_ONE_OF_THREE_IN_FULL);

    // FOP reports a missing glyph as an EVENT, not an exception, and a silent .notdef box is
    // exactly the failure mode this gate exists to catch — so the event log is part of the proof.
    //
    // Section 8.5, THE NEGATIVE CONTROL, and it is the reason this assertion names two characters
    // instead of forbidding all of them. Research R-5 predicted from source that FOP's
    // BidiConstants defines no isolate classes, its bidi implementation predating Unicode 6.3, and
    // that FSI/PDI would therefore not isolate and might additionally surface as boxes. Measured
    // here: U+2068 and U+2069 are the ONLY missing-glyph events in the whole document. The
    // prediction is confirmed live, which is why the renderer must never carry the FSI/PDI idiom
    // from mobile/lib/core/text/ltr_value.dart across into an FO template.
    List<String> missingGlyphs =
        events.stream().filter(event -> event.contains("Glyph")).distinct().toList();
    assertThat(missingGlyphs)
        .as("section 8.5 — FSI/PDI are missing glyphs under FOP, and nothing else is")
        .hasSize(2)
        .allMatch(event -> event.contains("0x2068") || event.contains("0x2069"));
  }

  /** Four positional forms and a lam-alef ligature, per section 8.1. */
  private static final String[] SHAPING_WORDS = {"استمارة", "بيانات", "مؤسسة", "لا", "مكتبة"};

  /** Synthetic. A Latin-script name is the case unicode-bidi="embed" has to keep readable. */
  private static final String LATIN_NAME = "SAMPLE TESTCASE NAME";

  /**
   * The footer line IN FULL, deliberately. Asserting the bare word «صفحة» would pass unchanged if
   * both {@code fo:page-number} and {@code fo:page-number-citation-last} resolved to nothing, which
   * is the exact failure section 8.4 exists to catch — the static word is always there.
   */
  private static final String PAGE_ONE_OF_THREE_IN_FULL = "صفحة 1 من 3";

  /**
   * The FO document. Written as a string ONLY because this is a spike with no customer data in it —
   * the renderer this gate unblocks must generate its FO with an escaping generator instead, never
   * by concatenation, because every field on the real form is customer- or scan-supplied (research
   * R-2).
   */
  private static String acceptanceFo() {
    String fsi = "⁨";
    String pdi = "⁩";
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" xml:lang="ar">
          <fo:layout-master-set>
            <fo:simple-page-master master-name="a4" page-width="210mm" page-height="297mm"
                margin-top="18mm" margin-bottom="18mm" margin-left="18mm" margin-right="18mm">
              <fo:region-body margin-bottom="14mm"/>
              <fo:region-after extent="10mm"/>
            </fo:simple-page-master>
          </fo:layout-master-set>

          <fo:page-sequence master-reference="a4" writing-mode="rl-tb"
              font-family="IBM Plex Sans Arabic" font-size="11pt" id="seq">

            <fo:static-content flow-name="xsl-region-after">
              <fo:block font-size="9pt" border-top="0.4pt solid #0b1c47" padding-top="2mm">
                <fo:inline>إصدارات القوائم المرجعية: المهن v4 · التقسيمات الإدارية v2</fo:inline>
                <fo:leader leader-pattern="space"/>
                <fo:inline>صفحة <fo:page-number/> من <fo:page-number-citation-last ref-id="seq"/></fo:inline>
              </fo:block>
            </fo:static-content>

            <fo:flow flow-name="xsl-region-body">

              <fo:block font-size="18pt" font-weight="bold" space-after="6mm">استمارة تحديث البيانات</fo:block>

              <fo:block font-weight="bold" space-after="2mm">1 - التشكيل والوصل</fo:block>
              <fo:block space-after="2mm">مؤسسة - بيانات - استمارة - مكتبة - جميل</fo:block>
              <fo:block space-after="6mm" font-size="16pt">لا - الله - أولاد - علا - إلا</fo:block>

              <fo:block font-weight="bold" space-after="2mm">2 - الاتجاه المختلط والأرقام</fo:block>
              <fo:block space-after="6mm">تم تحديث بيانات الحساب %s والهاتف %s تحت الرقم المرجعي %s بنجاح.</fo:block>

              <fo:block font-weight="bold" space-after="2mm">3 - جدول التسمية والقيمة</fo:block>
              <fo:table table-layout="fixed" width="100%%" space-after="6mm">
                <fo:table-column column-width="40mm"/>
                <fo:table-column column-width="proportional-column-width(1)"/>
                <fo:table-body>
        %s
                </fo:table-body>
              </fo:table>

              <fo:block break-before="page" font-weight="bold" space-after="2mm">4 - الضابط السالب لمحارف العزل</fo:block>
              <fo:block space-after="2mm">بدون محارف العزل: الرقم المرجعي %s داخل جملة عربية.</fo:block>
              <fo:block space-after="2mm">مع محارف العزل: الرقم المرجعي %s%s%s داخل جملة عربية.</fo:block>
              <fo:block space-after="6mm" font-size="9pt">إن ظهرت مربعات أو اختفت القيمة في السطر الثاني فهذا هو ما تنبأ به البحث.</fo:block>

              <fo:block break-before="page" font-weight="bold" space-after="2mm">5 - علامة الزائد في بداية رقم الهاتف</fo:block>
              <fo:block space-after="2mm" font-size="9pt">المطلوب: أن تظهر علامة + على يسار الرقم في كل الحالات.</fo:block>
              <fo:block space-after="2mm">أ - بدون معالجة: %s</fo:block>
              <fo:block space-after="2mm">ب - داخل كتلة بخاصية lr-tb:</fo:block>
              <fo:block space-after="2mm" writing-mode="lr-tb" text-align="left">%s</fo:block>
              <fo:block space-after="2mm">ج - داخل fo:bidi-override باتجاه ltr: <fo:bidi-override direction="ltr" unicode-bidi="bidi-override">%s</fo:bidi-override></fo:block>
              <fo:block space-after="2mm">د - داخل fo:bidi-override بخاصية embed: <fo:bidi-override direction="ltr" unicode-bidi="embed">%s</fo:bidi-override></fo:block>
              <fo:block space-after="6mm">هـ - نفس الطريقة مع اسم لاتيني: <fo:bidi-override direction="ltr" unicode-bidi="embed">%s</fo:bidi-override></fo:block>

            </fo:flow>
          </fo:page-sequence>
        </fo:root>
        """
        .formatted(
            ACCOUNT,
            PHONE,
            REFERENCE,
            labelValueRows(),
            REFERENCE,
            fsi,
            REFERENCE,
            pdi,
            PHONE,
            PHONE,
            PHONE,
            PHONE,
            LATIN_NAME);
  }

  private static String labelValueRows() {
    String[][] rows = {
      {"رقم الحساب", ACCOUNT},
      {"الرقم الوطني", "000-0000-0001"},
      {"التلفون", PHONE},
      {"الرقم المرجعي", REFERENCE},
      {"المدينة", "أم درمان"},
      {"حقل بلا قيمة", "غير متاح"},
    };
    StringBuilder fo = new StringBuilder();
    for (String[] row : rows) {
      // The VALUE cell carries the mechanism this render SETTLED ON, so that page 1 shows the
      // form's real shape rather than the defect: fo:bidi-override with unicode-bidi="embed".
      //
      // It deliberately does NOT use the writing-mode="lr-tb" block that research section 6
      // recommended. That spelling is still in this document, once, as case (ب) on page 3, where
      // it stands beside the others as the evidence that it does not work - a leading + comes out
      // on the far end. Keeping it here as well would have put a known defect on the sample form
      // under a comment recommending it.
      fo.append(
          """
                  <fo:table-row>
                    <fo:table-cell padding="1mm" border-bottom="0.2pt solid #cccccc">
                      <fo:block font-size="9pt" color="#5980a6">%s</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="1mm" border-bottom="0.2pt solid #cccccc">
                      <fo:block><fo:bidi-override direction="ltr" unicode-bidi="embed">%s</fo:bidi-override></fo:block>
                    </fo:table-cell>
                  </fo:table-row>
          """
              .formatted(row[0], row[1]));
    }
    return fo.toString();
  }

  private static byte[] render(String fo, List<String> events) throws Exception {
    FopFactory fopFactory = FopFactoryProvider.create();

    ByteArrayOutputStream pdf = new ByteArrayOutputStream();
    Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdf);

    EventListener listener =
        (Event event) -> events.add(event.getEventGroupID() + ": " + EventFormatter.format(event));
    fop.getUserAgent().getEventBroadcaster().addEventListener(listener);

    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.transform(
        new StreamSource(new ByteArrayInputStream(fo.getBytes(StandardCharsets.UTF_8))),
        new SAXResult(fop.getDefaultHandler()));

    return pdf.toByteArray();
  }
}
