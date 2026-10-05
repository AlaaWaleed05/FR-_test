package com.sfbank.bayanati.printedform.service;

import com.sfbank.bayanati.printedform.domain.PrintedChip;
import com.sfbank.bayanati.printedform.domain.PrintedField;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedImage;
import com.sfbank.bayanati.printedform.domain.PrintedSection;
import com.sfbank.bayanati.printedform.domain.PrintedValue;
import java.io.StringWriter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Turns a {@link PrintedFormDocument} into the XSL-FO that Apache FOP renders. Pure: no Spring
 * context, no database, no network and no clock, so a plain JUnit test exercises it directly —
 * which is what CLAUDE.md's package rule means by business logic.
 *
 * <h2>Escaping is the whole point of using a writer</h2>
 *
 * <p>Every field on this form is customer- or scan-supplied. A name containing {@code &} or {@code
 * <} would break the FO document, and a crafted one could inject markup into it (research R-2). So
 * the FO is produced by {@link XMLStreamWriter}, whose {@code writeCharacters} escapes, and NEVER
 * by string concatenation. There is no {@code append("<fo:block>" + value)} anywhere in this class,
 * and there must not be one added: the moment a single value is concatenated, the guarantee is gone
 * for the whole document, not just that value.
 *
 * <h2>Direction, and the rule that was corrected by measurement</h2>
 *
 * <p>Every line pairs an Arabic label with something Latin — an account number, a {@code +249}
 * phone number, a reference, a date. A value marked {@link PrintedValue#latinScript()} is wrapped
 * in {@code <fo:bidi-override direction="ltr" unicode-bidi="embed">}.
 *
 * <p>That is NOT what the toolchain research recommended. Section 6 said to give such a value its
 * own block with {@code writing-mode="lr-tb"} and insert no control characters. Rendered at S8-33
 * and read, that leaves {@code +249912345678} printed as {@code 249912345678+}. The finding, the
 * five spellings compared and the reason {@code embed} beats {@code bidi-override} are in {@code
 * docs/components/pdf-rendering.md} and in {@code FopArabicAcceptanceRenderTest}.
 *
 * <p><strong>Never use FSI/PDI here.</strong> {@code mobile/lib/core/text/ltr_value.dart} has an
 * {@code isolate} helper built on U+2068/U+2069 and it must not be carried across. FOP's {@code
 * BidiConstants} defines no isolate classes — its bidi predates Unicode 6.3 — so the characters
 * have no isolating effect at all and each raises a missing-glyph event. Confirmed live, not
 * inferred (research R-5).
 */
public class FoDocumentWriter {

  private static final String FO = "http://www.w3.org/1999/XSL/Format";

  // FOP's extension namespace, fox:, WAS declared here. It existed for exactly one purpose --
  // sizing the AZ watermark's region background, which standard XSL-FO 1.1 cannot do -- and AD-022
  // (j) removed the watermark at S9-06, so nothing in this document uses the extension any more.
  //
  // Kept as a note rather than a dead constant, for whoever adds the next fox: attribute: the
  // namespace must be BOUND with setPrefix AND declared with writeNamespace, not one or the other.
  // This XMLStreamWriter is non-repairing, so writeAttribute(FOX, ...) with no prefix bound throws
  // "Prefix cannot be null", which write() turns into an IllegalStateException and the render dies.

  // ---- The approved AZ palette --------------------------------------------------------------
  //
  // Sampled and approved in docs/backoffice-redesign.md §1, and replacing the inherited SFB navy
  // (#0b1c47) and its steel/band/hairline family wholesale. AD-022 ruling 2 makes the approved
  // design the build target, and "branded AZ" means these values -- the brief names the three
  // headline tokens and the artboards carry the rest.
  //
  // THE ARTBOARDS EXPRESS THE SUPPORTING ROLES AS rgba() OVER WHITE, AND XSL-FO HAS NO rgba().
  // Each one below is therefore the artboard's own alpha composited onto the page's white ground,
  // which is what the browser draws and so is a translation rather than a choice: MUTED is
  // rgba(32,33,42,0.64), FAINT 0.48, BORDER 0.26, HAIRLINE 0.08, and the two purple grounds are
  // rgba(75,35,126,0.07) and 0.05. The form never paints over a non-white ground, so the blend is
  // exact rather than approximate.

  /** Body ink, and the page-sequence default every block inherits. */
  private static final String INK = "#20212A";

  /** Primary accent: the reference number, the rule bar's long arm, sub-heading rules. */
  private static final String PURPLE = "#4B237E";

  /**
   * The rule bar's short arm. It has no counterpart in the AZ sign-in palette and comes from the
   * letterhead; §1 records that it stays until the product owner supplies a replacement.
   */
  private static final String RED = "#E4312A";

  /** Labels, badges, captions — rgba(ink, 0.64). */
  private static final String MUTED = "#707177";

  /** Footer text and absent values — rgba(ink, 0.48). */
  private static final String FAINT = "#949499";

  /** Section and table borders — rgba(ink, 0.26). */
  private static final String BORDER = "#C5C5C8";

  /** The rule under each field row — rgba(ink, 0.08). */
  private static final String HAIRLINE = "#EDEDEE";

  /** The section heading's ground — rgba(purple, 0.07). */
  private static final String BAND = "#F2F0F6";

  /** An image tile's ground and its border — rgba(purple, 0.05) and rgba(ink, 0.14). */
  private static final String TILE = "#F6F4F9";

  private static final String TILE_BORDER = "#E0E0E1";

  /** The liveness chip, and the only green on the form. */
  private static final String SUCCESS = "#2F7D5D";

  /** A4, confirmed rather than assumed — ticket 08 decision 5. Sudan uses A4. */
  private static final String PAGE_WIDTH = "210mm";

  private static final String PAGE_HEIGHT = "297mm";

  private static final String FONT = "IBM Plex Sans Arabic";

  /**
   * The bank's roundel, and the header's only square image. AD-022 (h), 2026-09-18.
   *
   * <p>It replaces the AZ lockup, which S9-03 had put here on the reasoning that AZ branding is
   * back-office-only and the printed form is a back-office artefact. True of the SCREEN, and the
   * back office keeps the AZ header; the product owner's ruling is that the PAPER a customer's
   * update is filed on carries the bank's identity, not the vendor's.
   *
   * <p>SQUARE, where the lockup was 410x280, and that is the one thing to keep in mind before
   * enlarging it. The band is 17mm on every page and the form is asserted at exactly two pages
   * (BL-162), so height here is the scarce resource — at {@code content-height} 9mm this occupies
   * 9mm of the 22mm column against the lockup's ~13mm, which is narrower, not taller. Raise the
   * height and the band grows on BOTH pages. The old SFB header stacked a 13mm roundel above the
   * bank name and needed 34mm; that is the shape this must not drift back into.
   *
   * <p>{@code brand/az-lockup.png} is left in the repository, unreferenced by this form. Removing a
   * committed brand asset is a decision about what the bank's resources hold, not a tidy-up.
   */
  private static final String LOGO = "classpath:brand/sfb-logo-circle.png";

  /**
   * The bank's name, drawn in the calligraphy of its own logo — an IMAGE, and necessarily so.
   *
   * <p>This is not a font and cannot be set as text: the lettering is part of the mark. Rendering
   * «البنك السوداني الفرنسي» in IBM Plex Sans Arabic, or in any other installed face, gives a
   * different shape from the logo it sits beside, which is why the name left the text layer
   * entirely at S9-06 and why {@code PrintedFormDocument.BANK_NAME_AR} went with it.
   *
   * <p>Cropped to the ARABIC LINE ONLY from the two-line design source, by {@code
   * backend/tool/prepare_brand_assets.py} — 617x129, ratio 4.78, wide and short. The Latin
   * "SUDANESE FRENCH BANK" beneath it in the source is not printed on this form.
   */
  private static final String WORDMARK = "classpath:brand/sfb-wordmark-ar-navy.png";

  // WATERMARK, WATERMARK_WIDTH and WATERMARK_HEIGHT were HERE. AD-022 (j), 2026-09-18: the AZ
  // watermark is off the page. brand/az-watermark.jpg stays in the repository, referenced by
  // nothing -- removing a committed brand asset is a decision about what the bank's resources hold,
  // not a tidy-up, and the same reasoning S9-03 applied to the SFB roundel it displaced.

  /**
   * The header band, on EVERY page since the two masters collapsed into one.
   *
   * <p>17mm is measured off the approved artboards, not chosen: rule bar, 10px of padding, a 34px
   * lockup, 6px of padding, the rule and a 10px margin come to 64px of a 794px page. Every page
   * gets it, which is what the artboards show and what makes the second master pointless.
   */
  private static final String HEADER_EXTENT = "17mm";

  // ---- Row density, and the second half of BL-162's fix ---------------------------------------
  //
  // The approved artboards set a field row's LABEL and VALUE both at 10px of a 794px-wide page,
  // which is 7.5pt. (They also set a field NUMBER at 8px; this form prints no numbers, so that
  // column has no counterpart here.) The form had been printing values at 9.5pt against 7.5pt
  // labels, a pairing that belongs to no artboard and that nothing had measured.
  //
  // It matters beyond looks. BL-162 is that section 3 runs two rows past page 2, and the page
  // furniture alone does not reclaim them: the identity band that AD-022 ruling (a) deletes is on
  // page 1, while section 3 starts page 2 behind a hard break, so deleting it frees space that was
  // never scarce. Measured on the three-page render: page 2 held 33 of 35 rows in 241mm with
  // essentially no slack. The one-master header and the artboard's margins give page 2 about ten
  // millimetres; two rows cost about twelve. Bringing the value size back toward the artboard's
  // supplies the rest, so the fix moves TOWARD the approved design rather than compromising with
  // it.

  private static final String ROW_VALUE_SIZE = "8pt";

  private static final String ROW_LABEL_SIZE = "7.5pt";

  private static final String ROW_PADDING = "0.7mm";

  /**
   * Every rule on the form: the section frame, the band around a heading, the line under a field
   * row and the divider between two columns. ONE width, and it is the artboard's own.
   *
   * <p><strong>0.75pt because the artboards draw 1px, and 1px of a 794px-wide page IS
   * 0.75pt.</strong> The rules were 0.4pt and the row rule 0.2pt, and the row rule in particular
   * was too thin to survive rasterising: 0.2pt is 0.36 of a pixel at the 130dpi the eyes-on render
   * uses, so it landed on some rows and disappeared on others. The product owner read exactly that
   * off the pages — "horizontal lines are not showing between all fields, its there between some
   * fields only" — and it is a rendering artefact of a hairline finer than the device, not a
   * missing border. Matching the artboard fixes it at every resolution rather than papering over
   * one.
   */
  private static final String RULE_WIDTH = "0.75pt";

  /**
   * How many images share a row before the next one wraps onto a new row.
   *
   * <p>FOUR since S9-03, matching the approved page 1, which sets its tiles in a single row of
   * four. It was three while the form carried five images in a three-and-two grid; the liveness
   * frame has since left the grid (AD-022) without leaving the bundle, so four tiles now divide
   * exactly.
   */
  private static final int IMAGES_PER_ROW = 4;

  /**
   * The block-progression extent of one image box on the form, present or absent.
   *
   * <p>ONE constant for the container and for the graphic inside it, so the two cannot drift apart.
   * They did: the box declared 34mm and the graphic 30mm, and neither number governed an absent
   * box, which came out at whatever its placeholder text needed.
   */
  private static final String IMAGE_BOX_HEIGHT = "30mm";

  /**
   * The render-scoped key an IMAGE salary certificate is bound under. Deliberately outside the
   * positional {@code image-N} range: the certificate is not one of the form's images and never
   * appears in the images section, only as an attachment page.
   */
  public static final String CERTIFICATE_IMAGE_KEY = "salary-certificate";

  /** The caption its attachment page carries. */
  public static final String CERTIFICATE_CAPTION = "شهادة المرتب";

  /**
   * Where a PDF salary certificate lands once PDFBox has stapled it onto the finished form, so the
   * BANK'S pages can say so.
   *
   * <p>None of this is knowable while the FO is being written for the first time — FOP has not
   * paginated yet and the certificate is not FOP's at all. {@code PrintedFormRenderer} lays the
   * form out once to learn how many sheets it runs to, then writes the FO again with these numbers
   * filled in. That second pass is the price of a footer that counts the whole bundle.
   *
   * @param totalPages every sheet in the finished file, the certificate's included
   * @param firstPage the sheet the certificate starts on, 1-based
   * @param lastPage the sheet it ends on; equal to {@code firstPage} for a one-page payslip
   */
  public record AppendedCertificate(int totalPages, int firstPage, int lastPage) {

    public AppendedCertificate {
      if (firstPage < 1 || lastPage < firstPage || totalPages < lastPage) {
        throw new IllegalArgumentException(
            "the certificate must occupy real sheets at the end of the bundle; got pages "
                + firstPage
                + "-"
                + lastPage
                + " of "
                + totalPages);
      }
    }

    /** «الصفحة 7 شهادة المرتب», or «الصفحات 7-9 شهادة المرتب» for more than one sheet. */
    public String note() {
      return firstPage == lastPage
          ? "الصفحة " + firstPage + " " + CERTIFICATE_CAPTION
          : "الصفحات " + firstPage + "-" + lastPage + " " + CERTIFICATE_CAPTION;
    }
  }

  private final XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();

  /**
   * @return the FO document. A {@code String} rather than a stream because it is a few tens of
   *     kilobytes of markup — the images are referenced, never inlined — and having it in hand is
   *     what lets a test assert against the markup instead of only against a rendered PDF.
   */
  public String write(PrintedFormDocument document) {
    // A nonce that belongs to no bound render. Only markup-inspecting callers use this overload;
    // an image URI built from it resolves to nothing, which is correct - there is nothing to
    // resolve.
    return write(document, "unbound");
  }

  /**
   * @param renderNonce the value {@code RenderScopedImages.bind} returned for THIS render. It makes
   *     every image URI unique, which is what stops FOP's factory-level image cache answering one
   *     customer's form with the previous customer's portrait. See {@link PrintedFormImageUris}.
   */
  public String write(PrintedFormDocument document, String renderNonce) {
    return write(document, renderNonce, null);
  }

  /**
   * @param appended where a PDF certificate will land once PDFBox has stapled it on, or null when
   *     nothing will be. Null means the footer's «من M» is FOP's own page citation, which is right
   *     only while FOP lays out every sheet in the file.
   */
  public String write(
      PrintedFormDocument document, String renderNonce, AppendedCertificate appended) {
    StringWriter out = new StringWriter();
    try {
      XMLStreamWriter xml = outputFactory.createXMLStreamWriter(out);
      xml.writeStartDocument("UTF-8", "1.0");
      xml.setPrefix("fo", FO);
      xml.writeStartElement(FO, "root");
      xml.writeNamespace("fo", FO);
      xml.writeAttribute("http://www.w3.org/XML/1998/namespace", "lang", "ar");

      layoutMasterSet(xml);
      pageSequence(xml, document, renderNonce, appended);

      xml.writeEndElement();
      xml.writeEndDocument();
      xml.flush();
    } catch (XMLStreamException cannotWriteFo) {
      throw new IllegalStateException("could not write the printed form's FO", cannotWriteFo);
    }
    return out.toString();
  }

  /**
   * ONE page master, where ticket 08 decision 7 had two.
   *
   * <p>That decision gave page one a full letterhead and continuation pages a compact header plus
   * «تابع من الصفحة السابقة». <strong>The approved artboards give page 2 the same full header as
   * page 1</strong> — identical rule bar, roundel, title, wordmark line, reference and date — so
   * there is nothing left for a second master to differ in. FO allows one {@code static-content}
   * per flow-name, so one master means one header, written once and repeated by FOP.
   *
   * <p>The continuation marker goes with it. It existed to say "this page is a continuation", which
   * a page carrying the whole letterhead and «صفحة 2 من 2» already says twice.
   *
   * <p><strong>The extent is the artboard's, measured, and it is what closes BL-162.</strong> The
   * old first-page header was 34mm of SFB letterhead — a 13mm roundel with the bank's name stacked
   * beneath it. This one sets a 9mm mark with the title and the bank's name BESIDE it, which
   * measures 64px of a 794px-wide artboard, or 16.9mm. The stack is the whole difference: S9-06 put
   * the bank's roundel back where the AZ lockup had been without putting the 34mm back with it.
   * Rounded to 17mm it gives page 2 five more millimetres than the 22mm continuation header it
   * replaces, rather than the twelve fewer that reusing 34mm would have cost.
   */
  private void layoutMasterSet(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeStartElement(FO, "layout-master-set");
    pageMaster(xml);
    xml.writeEndElement();
  }

  /**
   * The page, and there is only one master — see {@link #layoutMasterSet}.
   *
   * <p><strong>The region body carries no background since S9-06.</strong> It held the AZ watermark
   * as a region background, sized by FOP's {@code fox:} extension because XSL-FO 1.1 gives {@code
   * background-image} no sizing property at all. AD-022 (j) removed it, and with it the only reason
   * this document declared the extension namespace.
   *
   * <p>What that measurement established is worth keeping even though the watermark is gone, since
   * a future background would hit all of it again: a plain {@code background-image} draws at the
   * asset's intrinsic PHYSICAL size, not the box's; the {@code fox:} width/height pair is the only
   * standard-FO-free way to size it; and a positioned {@code block-container} holding a scaled
   * {@code external-graphic} reaches the same size but paints AFTER the body, so it draws ON TOP of
   * the form rather than behind it. Only a region background is genuinely behind the content.
   *
   * <p>Margins are the artboard's: 40px of a 794px page is 10.6mm, and its 34px top padding 9mm.
   * The 8mm foot is what the artboard leaves below its footer rule.
   */
  private void pageMaster(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeStartElement(FO, "simple-page-master");
    xml.writeAttribute("master-name", "form");
    xml.writeAttribute("page-width", PAGE_WIDTH);
    xml.writeAttribute("page-height", PAGE_HEIGHT);
    xml.writeAttribute("margin-top", "9mm");
    xml.writeAttribute("margin-bottom", "8mm");
    xml.writeAttribute("margin-left", "10.6mm");
    xml.writeAttribute("margin-right", "10.6mm");

    xml.writeStartElement(FO, "region-body");
    // The body must clear both regions or it overprints them; FOP does not do this for you.
    xml.writeAttribute("margin-top", HEADER_EXTENT);
    xml.writeAttribute("margin-bottom", "12mm");
    xml.writeEndElement();

    xml.writeStartElement(FO, "region-before");
    xml.writeAttribute("region-name", "header");
    xml.writeAttribute("extent", HEADER_EXTENT);
    xml.writeEndElement();

    xml.writeStartElement(FO, "region-after");
    xml.writeAttribute("region-name", "footer");
    xml.writeAttribute("extent", "10mm");
    xml.writeEndElement();

    xml.writeEndElement();
  }

  private void pageSequence(
      XMLStreamWriter xml,
      PrintedFormDocument document,
      String renderNonce,
      AppendedCertificate appended)
      throws XMLStreamException {
    xml.writeStartElement(FO, "page-sequence");
    xml.writeAttribute("master-reference", "form");
    // rl-tb is what puts the labels on the right and the page number on the correct side of the
    // footer. The `direction` and `unicode-bidi` PROPERTIES are not implemented by FOP; direction
    // is expressed by writing-mode and by fo:bidi-override.
    xml.writeAttribute("writing-mode", "rl-tb");
    xml.writeAttribute("font-family", FONT);
    xml.writeAttribute("font-size", "9pt");
    xml.writeAttribute("color", INK);
    // The id is what fo:page-number-citation-last resolves "of M" against.
    xml.writeAttribute("id", "form");

    header(xml, document);
    footer(xml, document, appended);
    body(xml, document, renderNonce);

    xml.writeEndElement();
  }

  /**
   * The approved header, on EVERY page. See {@link #layoutMasterSet} for why there is only one.
   *
   * <p>Reading from the start (right) edge: the two-colour rule bar, then the bank's roundel with
   * the form's title beside it and, under the title, the bank's wordmark followed by the app's
   * name; at the end (left) edge the reference number in purple and the print date under it.
   *
   * <p><strong>The rule bar is 2:7 red to purple and the proportion is the artboard's.</strong> §1
   * of the brief records that the grey {@code #ABADAC} of the earlier mock is removed and the
   * purple takes its width, so this is two cells of a fixed table rather than three.
   *
   * <p><strong>The reference number is here because the identity band no longer exists.</strong>
   * AD-022 ruling (a) reduced that band to a compact line of name and times, and the single-master
   * collapse removed the continuation header — between them the two places the reference used to be
   * printed. The footer carries it as well; see {@link PrintedFormDocument#referenceNumber()} for
   * why the duplication is deliberate.
   */
  private void header(XMLStreamWriter xml, PrintedFormDocument document) throws XMLStreamException {
    xml.writeStartElement(FO, "static-content");
    xml.writeAttribute("flow-name", "header");

    xml.writeStartElement(FO, "block");

    ruleBar(xml);

    // Brand marks and titles on the start (right) side, reference and date on the end (left)
    // side. A two-cell table rather than a float: FO has no float, and a table is how the
    // artboard's space-between is expressed.
    startTable(xml, List.of("62%", "38%"));
    xml.writeStartElement(FO, "table-row");

    // The roundel, the title and the wordmark line share a row so the header stays one band 17mm
    // deep. The old SFB header stacked a roundel ABOVE the bank name, which is most of why it
    // needed 34mm -- putting the roundel back without putting the stack back is the whole trick.
    cellStart(xml);
    startTable(xml, List.of("22mm", "auto"));
    xml.writeStartElement(FO, "table-row");

    xml.writeStartElement(FO, "table-cell");
    xml.writeAttribute("display-align", "center");
    xml.writeStartElement(FO, "block");
    xml.writeStartElement(FO, "external-graphic");
    xml.writeAttribute("src", "url(" + LOGO + ")");
    xml.writeAttribute("content-height", "9mm");
    xml.writeAttribute("content-width", "scale-to-fit");
    xml.writeAttribute("scaling", "uniform");
    xml.writeEndElement();
    xml.writeEndElement();
    xml.writeEndElement();

    xml.writeStartElement(FO, "table-cell");
    xml.writeAttribute("display-align", "center");
    xml.writeAttribute("padding-start", "2mm");
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", "10.5pt");
    xml.writeAttribute("font-weight", "bold");
    xml.writeCharacters(PrintedFormDocument.TITLE);
    xml.writeEndElement();
    // The bank's wordmark and the app's name, on the line that used to carry «البنك السوداني
    // الفرنسي — للاستخدام الداخلي» as muted text. AD-022 (h) and (i), 2026-09-18.
    //
    // The wordmark is an image because the bank's name is DRAWN, not typeset (see WORDMARK), and
    // «بياناتي» is text because it is a word — set in Amiri, the face the mobile splash uses, so
    // the paper names the app the way the app does.
    //
    // 4mm, where the muted line it replaces was 6.5pt text (2.29mm). MEASURED out of FOP's area
    // tree rather than eyeballed, because the band is on BOTH pages and the form is asserted at
    // exactly two (BL-162): the header's content comes to 14.30mm inside a 17mm band, so 2.70mm
    // is the whole margin this line has to grow into, and page 1 keeps 50.71mm of body slack.
    // HEADER_EXTENT did not have to change. Raising this is what re-inflates the header — see LOGO.
    //
    // In RTL the first child sits at the RIGHT, so this reads wordmark-then-name, which is the
    // order the ruling sets.
    xml.writeStartElement(FO, "block");
    xml.writeStartElement(FO, "external-graphic");
    xml.writeAttribute("src", "url(" + WORDMARK + ")");
    xml.writeAttribute("content-height", "4mm");
    xml.writeAttribute("content-width", "scale-to-fit");
    xml.writeAttribute("scaling", "uniform");
    xml.writeAttribute("vertical-align", "middle");
    xml.writeEndElement();
    // font-weight PINNED, and not decoration: Amiri is registered Regular-only (fop.xconf says
    // why), so a bold value inherited from anywhere finds no match, FOP substitutes a 700 face,
    // and Times has no Arabic at all -- the name would vanish with no error. The S8-33 failure,
    // in the one span still open to it.
    xml.writeStartElement(FO, "inline");
    xml.writeAttribute("font-family", "Amiri");
    xml.writeAttribute("font-size", "9pt");
    xml.writeAttribute("font-weight", "normal");
    xml.writeAttribute("color", PURPLE);
    xml.writeAttribute("padding-start", "2mm");
    xml.writeAttribute("vertical-align", "middle");
    xml.writeCharacters(PrintedFormDocument.APP_NAME_AR);
    xml.writeEndElement();
    xml.writeEndElement();
    xml.writeEndElement();

    xml.writeEndElement();
    endTable(xml);
    cellEnd(xml);

    xml.writeStartElement(FO, "table-cell");
    xml.writeAttribute("padding", "0.8mm");
    xml.writeAttribute("display-align", "center");
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("text-align", "end");
    xml.writeAttribute("font-size", "9pt");
    xml.writeAttribute("font-weight", "bold");
    xml.writeAttribute("color", PURPLE);
    latin(xml, document.referenceNumber());
    xml.writeEndElement();
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("text-align", "end");
    xml.writeAttribute("font-size", "6.5pt");
    xml.writeAttribute("color", MUTED);
    xml.writeCharacters(PrintedFormDocument.DATE_LABEL);
    latin(xml, document.printedOn());
    xml.writeEndElement();
    xml.writeEndElement();

    xml.writeEndElement();
    endTable(xml);

    rule(xml, RULE_WIDTH, BORDER);

    xml.writeEndElement();
    xml.writeEndElement();
  }

  /**
   * The two-colour rule across the top of every page: red for two parts, purple for seven.
   *
   * <p>A fixed two-column table whose cells carry a background and an empty block. A {@code
   * border-top} cannot do this — one border takes one colour — and a {@code leader} draws a line of
   * the CURRENT colour with no way to change it mid-run.
   */
  private void ruleBar(XMLStreamWriter xml) throws XMLStreamException {
    startTable(xml, List.of("22.2%", "77.8%"));
    xml.writeStartElement(FO, "table-row");
    for (String colour : List.of(RED, PURPLE)) {
      xml.writeStartElement(FO, "table-cell");
      xml.writeAttribute("background-color", colour);
      xml.writeAttribute("height", "0.8mm");
      xml.writeStartElement(FO, "block");
      xml.writeAttribute("font-size", "0.8mm");
      xml.writeAttribute("line-height", "0.8mm");
      xml.writeEndElement();
      xml.writeEndElement();
    }
    xml.writeEndElement();
    endTable(xml);
  }

  /**
   * The footer: «صفحة N من M» on every page, and — when a PDF certificate is stapled on behind the
   * form — a note naming the sheet it lands on.
   *
   * <p><strong>M comes from one of two places, and which one is the whole point.</strong> With
   * nothing appended it is {@code fo:page-number-citation-last}, resolved by FOP over the document
   * FOP laid out. That is wrong the moment a certificate is appended afterwards by PDFBox, because
   * FOP never sees those sheets: a seven-sheet bundle said «صفحة 6 من 6» on its last numbered page
   * and its seventh sheet carried nothing at all.
   *
   * <p>Product-owner ruling, 2026-09-14: the certificate keeps its bare sheet — it is the
   * customer's own document and the bank does not write on it — and the BANK'S pages carry the
   * honest total plus a note saying which sheet the payslip is. So when {@code appended} is given,
   * M is a literal computed by the renderer from both halves, and the note reads «الصفحة 7 شهادة
   * المرتب» (or «الصفحات 7-9 …» for a multi-page one).
   *
   * <p>The note sits under the three-part line rather than inside it, because none of the three
   * parts is its and squeezing it into one would push that part off centre.
   *
   * <h2>Three parts, and why a table rather than a leader</h2>
   *
   * <p>The approved footer reads: product and reference · «طبع بواسطة الموظف: <em>username</em>» ·
   * «صفحة N من M». <strong>A leader cannot centre the middle part.</strong> The old single-line
   * idiom justified two ends with a stretching space between them, which gives a true start and a
   * true end and no true centre — the middle would sit wherever the first part's length left it,
   * moving page to page as the note came and went. Three fixed cells put the operator's name in the
   * same place on every sheet.
   *
   * <p><strong>The middle part is AD-022 ruling 2's whole justification.</strong> The ruling
   * removed the ATTRIBUTED/UNATTRIBUTED choice on the grounds that the approved form always names
   * the operator who printed it; this line is that promise, and it had no implementation until
   * S9-03. {@code printedBy} is genuinely the PRINTING operator, not a per-field editor — see
   * {@link #editedMarker} for why no name appears beside a row.
   */
  private void footer(
      XMLStreamWriter xml, PrintedFormDocument document, AppendedCertificate appended)
      throws XMLStreamException {
    xml.writeStartElement(FO, "static-content");
    xml.writeAttribute("flow-name", "footer");

    xml.writeStartElement(FO, "block");
    rule(xml, RULE_WIDTH, HAIRLINE);

    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", "6pt");
    xml.writeAttribute("color", FAINT);

    // NO reference-list versions line. Product-owner ruling, 2026-09-14: ticket 04 decision 3 put
    // them here so "a reprint in a year says the same thing", and the product owner read the
    // rendered footer and ruled the version noise off the paper. Nothing is lost from the RECORD -
    // ticket 09 decision 2 puts the same versions in the print audit event payload, which is where
    // a reprint would be checked against anyway.

    startTable(xml, List.of("40%", "30%", "30%"));
    xml.writeStartElement(FO, "table-row");

    // Start (right): the product, the form's name and the reference.
    footerCell(xml, "start");
    xml.writeCharacters(
        PrintedFormDocument.PRODUCT_NAME + " · " + PrintedFormDocument.TITLE + " — ");
    latin(xml, document.referenceNumber());
    cellEnd(xml);
    xml.writeEndElement();

    // Centre: who printed it.
    footerCell(xml, "center");
    xml.writeCharacters(PrintedFormDocument.PRINTED_BY_LABEL);
    latin(xml, document.printedBy());
    cellEnd(xml);
    xml.writeEndElement();

    // End (left): «صفحة N من M».
    footerCell(xml, "end");
    xml.writeCharacters("صفحة ");
    xml.writeEmptyElement(FO, "page-number");
    xml.writeCharacters(" من ");
    if (appended == null) {
      xml.writeStartElement(FO, "page-number-citation-last");
      xml.writeAttribute("ref-id", "form");
      xml.writeEndElement();
    } else {
      xml.writeCharacters(Integer.toString(appended.totalPages()));
    }
    cellEnd(xml);
    xml.writeEndElement();

    xml.writeEndElement();
    endTable(xml);

    if (appended != null) {
      block(xml, appended.note(), "6pt", FAINT, false);
    }

    xml.writeEndElement();
    xml.writeEndElement();
    xml.writeEndElement();
  }

  /** One footer cell, open and ready for characters. The caller closes the block and the cell. */
  private void footerCell(XMLStreamWriter xml, String align) throws XMLStreamException {
    xml.writeStartElement(FO, "table-cell");
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("text-align", align);
  }

  private void body(XMLStreamWriter xml, PrintedFormDocument document, String renderNonce)
      throws XMLStreamException {
    xml.writeStartElement(FO, "flow");
    xml.writeAttribute("flow-name", "xsl-region-body");

    // The navy attribution banner that stood here is GONE with AD-022 (S9-01), and it is removed
    // rather than shown unconditionally.
    //
    // Stated carefully, because an earlier draft of this comment got it wrong and @agent-reviewer
    // caught it: the banner DID render, and the name beside a manual field DID appear. What it
    // named was `manualCompletionOperator`, the profile-level operator who completed the profile,
    // applied to every manual field on it -- true at PROFILE granularity, not the per-field editor
    // the banner's wording implies. It rendered only on the ATTRIBUTED variant, and the variant
    // the print menu DEFAULTED to was the unattributed one.
    //
    // It goes for two reasons. Every print is now the form that shipped as that default, which
    // never carried this banner, so nothing is disclosed less than an operator got by pressing
    // print and accepting it. And AD-022's first ruling deleted manual completion, so no profile
    // can be `manual`, no field can be marked manual, and the banner would announce a per-field
    // attribution that can no longer occur on any page it printed on.
    //
    // The printer's own attribution is NOT lost, and since S9-03 it is in the page FOOTER, where
    // the approved design puts it and where every sheet carries it rather than only the first.
    compactIdentity(xml, document);

    // EACH SECTION'S ROWS SIT INSIDE A FRAME, and the frame spans a section's SUB-HEADINGS too.
    //
    // That grouping is the whole reason this loop opens and closes the frame itself rather than
    // letting `section` draw one. The approved page 2 puts a SINGLE rule around the whole of
    // section 3 — all six sub-headings and all thirty-five rows inside one box — not six boxes.
    // The document model has no notion of a sub-heading belonging to the heading above it; it is
    // a flat list where `isSubHeading` is a property of each entry. So the run is what defines the
    // frame: a top-level section opens one, every sub-heading that follows continues it, and the
    // next top-level section closes it.
    //
    // THE IMAGE GRID BELONGS TO THE PAGE THE SECTIONS BEFORE IT ARE ON, and until S9-03 nothing
    // had to say so: the grid was written after every section because no section ever started a
    // page. Section 3 now does, so a grid still written last would follow it onto page 2 -- and
    // the approved page 1 is header, section 1, section 2, the verification chips, then the four
    // tiles. Flushing them before the first page-breaking section is what keeps them on page 1
    // without the document model having to name pages, which it deliberately does not. The frame
    // closes first: the chips and tiles are not part of section 2 and must not be boxed with it.
    boolean gridWritten = false;
    boolean framed = false;
    for (PrintedSection section : document.sections()) {
      if (section.breakBefore() && !gridWritten) {
        framed = closeFrame(xml, framed);
        verificationChips(xml, document);
        images(xml, document, renderNonce);
        gridWritten = true;
      }
      if (section.isSubHeading()) {
        // A sub-heading with nothing under it is not written at all -- see sectionHeading. Left
        // outside the frame logic because it must not close the run it sits in.
        if (section.fields().isEmpty()) {
          continue;
        }
        sectionHeading(xml, section);
      } else {
        framed = closeFrame(xml, framed);
        sectionHeading(xml, section);
        openFrame(xml);
        framed = true;
      }
      sectionFields(xml, section, document);
    }
    framed = closeFrame(xml, framed);

    if (!gridWritten) {
      verificationChips(xml, document);
      images(xml, document, renderNonce);
    }
    attachments(xml, document, renderNonce);

    xml.writeEndElement();
  }

  /**
   * The rule around a section's rows, as the approved artboards draw it.
   *
   * <p>No border on the BEFORE edge: the heading band immediately above already carries one all
   * round, and two hairlines meeting would print as a double rule. The artboards say the same thing
   * in CSS — the grid under each heading is {@code border: 1px solid …; border-top: 0}.
   */
  private void openFrame(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("border-width", RULE_WIDTH);
    xml.writeAttribute("border-style", "solid");
    xml.writeAttribute("border-color", BORDER);
    xml.writeAttribute("border-before-width", "0pt");
    xml.writeAttribute("space-after", "1.6mm");
    indent(xml);
  }

  /**
   * @return false, so the caller can assign it back and keep the flag honest at every exit.
   */
  private boolean closeFrame(XMLStreamWriter xml, boolean framed) throws XMLStreamException {
    if (framed) {
      xml.writeEndElement();
    }
    return false;
  }

  /**
   * ONE LINE: the customer, when they submitted, when this was printed. AD-022 ruling (a).
   *
   * <p>What stood here was a six-line band — a filled panel with the name at 13pt beside the
   * reference at 13pt, over a metadata row of four items. Three of those four are gone and each for
   * its own reason, none of them "it was too big":
   *
   * <ul>
   *   <li>the REFERENCE, because the header and the footer now carry it on every page, where the
   *       band carried it on one;
   *   <li>«المشغّل الطابع», because the footer names the same person on every page — that move is
   *       AD-022 ruling 2's promise being kept, not a deletion;
   *   <li>«مصدر البيانات», because ruling 1 deleted manual completion, so every profile is digital
   *       and a label that cannot vary tells a reader nothing.
   * </ul>
   *
   * <p>The artboards show no identity line at all. Ruling (a) adds this one back deliberately: a
   * filed sheet has to say whose it is and when it was taken, and the artboards' header says
   * neither.
   */
  private void compactIdentity(XMLStreamWriter xml, PrintedFormDocument document)
      throws XMLStreamException {
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", "7pt");
    xml.writeAttribute("color", MUTED);
    xml.writeAttribute("space-after", "1.5mm");

    xml.writeStartElement(FO, "inline");
    xml.writeAttribute("font-size", "9pt");
    xml.writeAttribute("font-weight", "bold");
    xml.writeAttribute("color", INK);
    xml.writeCharacters(document.customerName());
    xml.writeEndElement();
    gap(xml);
    metaItem(xml, "التقديم: ", document.submittedAt(), true);
    gap(xml);
    metaItem(xml, "الطباعة: ", document.printedAt(), true);

    xml.writeEndElement();
  }

  private void metaItem(XMLStreamWriter xml, String label, String value, boolean latinValue)
      throws XMLStreamException {
    xml.writeCharacters(label);
    xml.writeStartElement(FO, "inline");
    xml.writeAttribute("color", INK);
    if (latinValue) {
      latin(xml, value);
    } else {
      xml.writeCharacters(value);
    }
    xml.writeEndElement();
  }

  /**
   * The two verification chips the approved page 1 sets between section 2 and the tiles.
   *
   * <p>Outline boxes, never filled: the artboards draw them that way, and a filled green chip on a
   * page of hairline rules reads as an alert rather than a statement of fact.
   *
   * <p>The WORDING and the TONE are the assembler's — see {@link
   * com.sfbank.bayanati.printedform.domain.PrintedChip}, which also records why the face-match
   * figures may never reach this method. An empty list prints nothing at all, which is what a
   * document with no verification to report should do.
   */
  private void verificationChips(XMLStreamWriter xml, PrintedFormDocument document)
      throws XMLStreamException {
    List<PrintedChip> chips = document.verificationChips();
    if (chips.isEmpty()) {
      return;
    }
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("space-before", "1.5mm");
    xml.writeAttribute("space-after", "1.5mm");
    xml.writeAttribute("font-size", "6.5pt");

    boolean first = true;
    for (PrintedChip chip : chips) {
      if (!first) {
        gap(xml);
      }
      first = false;
      String colour =
          switch (chip.tone()) {
            case GOOD -> SUCCESS;
            case BAD -> RED;
            case NEUTRAL -> MUTED;
          };
      xml.writeStartElement(FO, "inline");
      xml.writeAttribute("color", colour);
      if (chip.tone() != PrintedChip.Tone.NEUTRAL) {
        xml.writeAttribute("font-weight", "bold");
      }
      xml.writeAttribute("border-width", RULE_WIDTH);
      xml.writeAttribute("border-style", "solid");
      xml.writeAttribute("border-color", chip.tone() == PrintedChip.Tone.NEUTRAL ? BORDER : colour);
      xml.writeAttribute("padding-start", "1.6mm");
      xml.writeAttribute("padding-end", "1.6mm");
      xml.writeAttribute("padding-before", "0.6mm");
      xml.writeAttribute("padding-after", "0.6mm");
      xml.writeCharacters(chip.text());
      xml.writeEndElement();
    }

    xml.writeEndElement();
  }

  /**
   * A section's heading only — the band, or the sub-heading's rule and word.
   *
   * <p>Split from its rows at S9-03 because the two now land on opposite sides of a border. A
   * top-level heading sits ABOVE the frame and carries its own rule all round; a sub-heading sits
   * INSIDE it. See {@code body} for why the frame is the loop's business and not this method's.
   */
  private void sectionHeading(XMLStreamWriter xml, PrintedSection section)
      throws XMLStreamException {
    boolean subHeading = section.isSubHeading();
    // A SUB-HEADING WITH NOTHING UNDER IT IS NOT WRITTEN. Section 3's «قنوات الاتصال» carries only
    // fields 25 and 26, and AD-022 ruling 4 removes both when no channel is verified -- which would
    // otherwise print a rule and a title over empty space. A SECTION heading with no fields still
    // prints: section 3's own heading is exactly that, and it introduces the six beneath it.
    if (subHeading && section.fields().isEmpty()) {
      return;
    }

    // keep-together on the heading would orphan a section title at a page foot; keep-with-next
    // ties it to at least the first row instead.
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("keep-with-next.within-page", "always");
    // Section 3 is the whole of page 2. Without this the form runs to whatever length its content
    // needs and stops being the approved TWO pages.
    if (section.breakBefore()) {
      xml.writeAttribute("break-before", "page");
    }
    indent(xml);
    if (subHeading) {
      // A sub-heading is a rule and a word, not a band: six filled bands down page 2 would read as
      // six sections and lose the one section they all sit under. PURPLE since S9-03 — the
      // artboards set these in the accent colour, which is what distinguishes them from the ink
      // of an ordinary row without giving them a ground of their own.
      xml.writeAttribute("border-after-width", RULE_WIDTH);
      xml.writeAttribute("border-after-style", "solid");
      xml.writeAttribute("border-after-color", BORDER);
      xml.writeAttribute("color", PURPLE);
      xml.writeAttribute("padding-bottom", "0.4mm");
      xml.writeAttribute("padding-start", "1mm");
      xml.writeAttribute("padding-end", "1mm");
      xml.writeAttribute("space-before", "1.6mm");
      xml.writeAttribute("space-after", "0.6mm");
      xml.writeAttribute("font-size", "7pt");
    } else {
      // A bordered band, as the artboards draw it — a hairline all round rather than the thick
      // start rule the SFB layout used.
      xml.writeAttribute("background-color", BAND);
      xml.writeAttribute("border-width", RULE_WIDTH);
      xml.writeAttribute("border-style", "solid");
      xml.writeAttribute("border-color", BORDER);
      xml.writeAttribute("padding", "1mm");
      xml.writeAttribute("space-before", "1.6mm");
      xml.writeAttribute("space-after", "0.8mm");
      xml.writeAttribute("font-size", "8.5pt");
    }
    xml.writeAttribute("font-weight", "bold");
    if (section.badge() != null) {
      // Without this the leader collapses to its optimum length and the badge sits beside the
      // title instead of against the far margin. Same mechanism, and the same trap, as footer().
      xml.writeAttribute("text-align-last", "justify");
    }
    xml.writeCharacters(section.title());
    // The badge sits at the FAR end of the same line, which is what the leader buys: «١ — بيانات
    // الهوية» at the start and «من السجل المدني» hard against the other margin.
    if (section.badge() != null) {
      xml.writeStartElement(FO, "leader");
      xml.writeAttribute("leader-pattern", "space");
      xml.writeAttribute("leader-length.maximum", "100%");
      xml.writeEndElement();
      xml.writeStartElement(FO, "inline");
      xml.writeAttribute("font-size", "6pt");
      xml.writeAttribute("font-weight", "normal");
      xml.writeAttribute("color", MUTED);
      xml.writeCharacters(section.badge());
      xml.writeEndElement();
    }
    xml.writeEndElement();
  }

  /**
   * A section's rows, inside whatever frame {@code body} has opened around them.
   *
   * <p>FIELD ORDER IS THE POINT. Ticket 04 decision 1 binds the form to field-provenance.md's own
   * order, so this walks the section's fields ONCE, in order, pairing only adjacent ones.
   */
  private void sectionFields(
      XMLStreamWriter xml, PrintedSection section, PrintedFormDocument document)
      throws XMLStreamException {
    //
    // It is worth knowing why this looks over-careful for what it now does. An earlier version
    // partitioned the fields into dual-source and single-valued and emitted each group, which
    // printed field 8 (mother's name) AFTER field 9 (sex). Every field was present and every value
    // correct, so no assertion about the form's CONTENT noticed - it was caught by looking at the
    // rendered page. Since 2026-09-14 no field is dual-source at all, so the shortcut would no
    // longer misorder anything; the in-order walk stays because it is the shape that cannot.
    List<PrintedField> pending = new java.util.ArrayList<>(2);
    for (PrintedField field : section.fields()) {
      if (!section.twoColumn()) {
        flush(xml, pending, document);
        fieldTable(xml, List.of(field), document);
        continue;
      }
      pending.add(field);
      if (pending.size() == 2) {
        flush(xml, pending, document);
      }
    }
    flush(xml, pending, document);
  }

  /** Emits whatever single-valued fields are waiting to be paired, then empties the buffer. */
  private void flush(XMLStreamWriter xml, List<PrintedField> pending, PrintedFormDocument document)
      throws XMLStreamException {
    if (pending.isEmpty()) {
      return;
    }
    fieldTable(xml, List.copyOf(pending), document);
    pending.clear();
  }

  /**
   * {@code margin="0"} on a block that carries a border or padding. One attribute, and without it
   * the block's rule sits somewhere other than where every neighbouring rule sits.
   *
   * <p><strong>XSL 1.1 §5.3.2, and it is not the CSS box model.</strong> {@code start-indent} and
   * {@code end-indent} are INHERITED, and a block's border and padding are folded into them only
   * when a margin is specified. With no margin the content rectangle stays at the inherited indent
   * and the border is drawn OUTSIDE it — so a block with {@code padding="1mm"} overhangs its
   * siblings by 1mm on each side, and one with only a border overhangs by the border width.
   *
   * <p>Measured out of FOP's area tree for a block in a 210mm region, which is the only way any of
   * this was settled:
   *
   * <pre>
   *   padding="1mm", no margin : ipd=538583  ipda=545751  space-start="-3584"
   *   padding="1mm", margin="0": ipd=531415  ipda=538583  start-indent="3584"
   * </pre>
   *
   * <p>The negative {@code space-start} is the overhang. It is what put the section band 1mm wider
   * than the frame beneath it — the product owner read that off the rendered page as "the size of
   * the frame is smaller than the width of the section title" — and, once the band was fixed by
   * other means, what put the six sub-heading rules 1mm PAST the frame they sit inside. Same
   * mechanism, twice, which is why it is one named helper and not an attribute copied about.
   *
   * <p>An earlier fix rebuilt the band as a one-cell table, on the reasoning that a table's width
   * IS its content rectangle. That worked and was five elements and a relocated page break to
   * achieve what this line achieves, on a diagnosis that was wrong about CSS as well.
   */
  private void indent(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeAttribute("margin", "0");
  }

  /**
   * The hairline under one cell of a field row. Applied to BOTH cells of every field, which is what
   * makes it read as a rule under the row rather than a stub under the value.
   */
  private void rowRule(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeAttribute("border-after-width", RULE_WIDTH);
    xml.writeAttribute("border-after-style", "solid");
    xml.writeAttribute("border-after-color", HAIRLINE);
  }

  /** One row carrying one or two fields, each as a label cell and a value cell. */
  private void fieldTable(
      XMLStreamWriter xml, List<PrintedField> fields, PrintedFormDocument document)
      throws XMLStreamException {
    List<String> widths =
        fields.size() == 2 ? List.of("17%", "33%", "17%", "33%") : List.of("17%", "83%");
    startTable(xml, widths);
    xml.writeStartElement(FO, "table-row");
    for (int column = 0; column < fields.size(); column++) {
      // The second field of a paired row opens a new COLUMN, and the artboards rule between the
      // two — border-inline-start on the second grid column. Without it the four cells read as one
      // run of four and the eye loses which value belongs to which label.
      labelCell(xml, fields.get(column), column > 0);
      valueCell(xml, fields.get(column), document);
    }
    xml.writeEndElement();
    endTable(xml);
  }

  private void labelCell(XMLStreamWriter xml, PrintedField field, boolean startsSecondColumn)
      throws XMLStreamException {
    // ROW_PADDING explicitly, NOT cellStart's shared 0.8mm. A table row is as tall as its tallest
    // cell, so a label cell left on the old padding would have gone on governing the row height
    // and the density constant would have been decorative -- the value cell it names would have
    // been the shorter of the two and changed nothing.
    xml.writeStartElement(FO, "table-cell");
    xml.writeAttribute("padding", ROW_PADDING);
    // THE SAME RULE THE VALUE CELL CARRIES, so the line runs the WHOLE width of the row.
    //
    // Only the value cell had it until now, which is 83% of a single-field row and 33% of each
    // half of a paired one -- so every separator stopped short of the label beside it and the eye
    // read the rows as unseparated. The product owner saw exactly that on the rendered pages. It
    // is a border on two cells rather than one on the row because FO gives fo:table-row no border
    // of its own that FOP honours.
    rowRule(xml);
    if (startsSecondColumn) {
      xml.writeAttribute("border-start-width", RULE_WIDTH);
      xml.writeAttribute("border-start-style", "solid");
      xml.writeAttribute("border-start-color", HAIRLINE);
      xml.writeAttribute("padding-start", "2mm");
    }
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", ROW_LABEL_SIZE);
    xml.writeAttribute("color", MUTED);
    // Nudges the smaller label onto the value's baseline. It was 0.8mm while the value was 9.5pt;
    // with the two sizes now half a point apart it barely has to do anything.
    xml.writeAttribute("padding-top", "0.2mm");
    xml.writeCharacters(field.label());
    xml.writeEndElement();
    cellEnd(xml);
  }

  private void valueCell(XMLStreamWriter xml, PrintedField field, PrintedFormDocument document)
      throws XMLStreamException {
    xml.writeStartElement(FO, "table-cell");
    xml.writeAttribute("padding", ROW_PADDING);
    rowRule(xml);

    // ONE value, never tagged. The product owner ruled on 2026-09-14 that each field takes a
    // single source, so there is no second value for an origin tag to distinguish and none is
    // printed. The «معدَّل» marker is unaffected: it says an operator changed the value after
    // submission, not which of two sources won.
    PrintedValue value = field.value();

    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", ROW_VALUE_SIZE);
    if (value.isAbsent()) {
      xml.writeAttribute("color", FAINT);
    }
    if (value.latinScript()) {
      latin(xml, value.text());
    } else {
      xml.writeCharacters(value.text());
    }
    if (field.edited()) {
      editedMarker(xml);
    }
    xml.writeEndElement();

    xml.writeEndElement();
  }

  /**
   * «معدَّل» beside a value an operator has keyed since submission.
   *
   * <p>An OUTLINE chip, not the filled one this used to be. The old marker was white on a solid
   * amber ground, which on a page of hairline rules read as a warning; the approved artboards set
   * it as a muted outline that trails the value, because an edited field is an ordinary event — a
   * customer's typo corrected at the counter — and not an exception the officer must act on.
   *
   * <p>It carries no name. {@code PrintedField.editedBy} holds one, and the approved form
   * deliberately does not print it: the footer names the operator who PRINTED the sheet, and a
   * second name beside a row would be a different claim wearing the same clothes.
   */
  private void editedMarker(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeCharacters(" ");
    xml.writeStartElement(FO, "inline");
    xml.writeAttribute("font-size", "6pt");
    xml.writeAttribute("color", MUTED);
    xml.writeAttribute("border-width", RULE_WIDTH);
    xml.writeAttribute("border-style", "solid");
    xml.writeAttribute("border-color", HAIRLINE);
    xml.writeAttribute("padding-start", "1mm");
    xml.writeAttribute("padding-end", "1mm");
    xml.writeCharacters(PrintedField.EDITED_MARKER);
    xml.writeEndElement();
  }

  /**
   * The FOUR images the approved page 1 tiles — document scan, document portrait, registry
   * portrait, signature — in a single row of four. The document carries FIVE: the liveness frame is
   * in the list and off the grid (AD-022), so it still prints as an appended sheet.
   *
   * <p>Ticket 04 decision 4 as reversed 2026-09-14. An absent image prints a bordered box SAYING it
   * is absent rather than an empty frame — the same reason decision 7 makes «غير متاح» mandatory
   * for text.
   *
   * <p>Tile ORDER is the artboard's since S9-03 — registry portrait, document portrait, document
   * face, signature — and it is {@code PrintedFormImageSlot.inFormOrder()} that states it, not the
   * enum's declaration order.
   */
  private void images(XMLStreamWriter xml, PrintedFormDocument document, String renderNonce)
      throws XMLStreamException {
    // An EMPTY list prints no image section, and that is a contract on the assembler rather than a
    // silent fallback here: ticket 04 decision 7's second half says a manually completed profile -
    // which has no scan, no registry lookup and no artifacts at all - must still SAY «غير متاح»
    // rather than show nothing. The assembler therefore passes three PrintedImage.absent entries
    // for such a profile, never an empty list. Empty means "this document has no image section",
    // which today no form does.
    if (document.images().isEmpty()) {
      return;
    }
    List<PrintedImage> images = document.images();
    // THE INDEX IS THE IMAGE'S KEY, not its position in the grid. imageCell builds the
    // render-scoped URI as "image-" + index against document.images(), so a filtered grid that
    // renumbered from zero would draw the right number of tiles with the WRONG bytes in them —
    // silently, since every tile would still carry a real image. Positions are collected; the
    // original index travels with each one.
    List<Integer> onPage = new java.util.ArrayList<>(images.size());
    for (int index = 0; index < images.size(); index++) {
      if (images.get(index).onPage()) {
        onPage.add(index);
      }
    }
    // Nothing to draw, so nothing is drawn — true of a document whose every image is
    // attachment-only.
    if (onPage.isEmpty()) {
      return;
    }

    // NO «الصور والتوقيع» BAND. It stood here until S9-03 and the approved page 1 has no such
    // heading: the tiles follow the verification chips directly, each captioned, and a section
    // band over four captioned pictures only says a third time what the pictures and their
    // captions already say. Removing it also buys back the last of page 1's crowding.
    startTable(xml, List.of("25%", "25%", "25%", "25%"));
    for (int first = 0; first < onPage.size(); first += IMAGES_PER_ROW) {
      xml.writeStartElement(FO, "table-row");
      for (int column = 0; column < IMAGES_PER_ROW; column++) {
        int slot = first + column;
        if (slot < onPage.size()) {
          int index = onPage.get(slot);
          imageCell(xml, images.get(index), index, renderNonce);
        } else {
          // A declared column with no cell is a malformed table. It is a defensive branch again
          // under the approved design: FOUR tiles in a four-column grid divide exactly, so nothing
          // pads today. It stays because the tile set is data — an absent slot still prints, but a
          // future ruling that drops one would otherwise produce a malformed table rather than a
          // visibly short row.
          cellStart(xml);
          xml.writeEmptyElement(FO, "block");
          cellEnd(xml);
        }
      }
      xml.writeEndElement();
    }
    endTable(xml);
  }

  /**
   * The attachment pages: each image again, one to a page, as large as the page allows.
   *
   * <p>Product-owner ruling, 2026-09-14. The operator is asked ONCE, at print time, whether to
   * print the attachments; if yes, these follow the form. The small copies stay on the form itself
   * — the product owner asked for both, the form being the filed record and the attachments being
   * what someone would actually read a signature or a document number off.
   *
   * <p><strong>An absent image gets no page.</strong> A manually completed profile has no artifacts
   * at all by design, so giving absences a page would print five sheets saying nothing. The form's
   * own images section still shows «غير متاح» for each, so the absence is not hidden — it is simply
   * not given a sheet of paper. Same shape as ticket 05 decision 9's rule for the certificate ("not
   * offered at all when the profile has no certificate").
   *
   * <p>The salary certificate is NOT here. It is the one attachment that may be a PDF, which FOP
   * cannot lay out, so it is appended to the finished document by {@code PrintedFormRenderer}.
   */
  private void attachments(XMLStreamWriter xml, PrintedFormDocument document, String renderNonce)
      throws XMLStreamException {
    if (!document.includeAttachments()) {
      return;
    }
    List<PrintedImage> images = document.images();
    for (int index = 0; index < images.size(); index++) {
      PrintedImage image = images.get(index);
      if (!image.isPresent()) {
        continue;
      }
      attachmentPage(xml, image.caption(), keyFor(index), renderNonce);
    }

    imageCertificatePage(xml, document, renderNonce);
  }

  /** One captioned image, alone on its own sheet. */
  private void attachmentPage(
      XMLStreamWriter xml, String caption, String imageKey, String renderNonce)
      throws XMLStreamException {
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("break-before", "page");
    xml.writeAttribute("text-align", "center");

    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", "11pt");
    xml.writeAttribute("font-weight", "bold");
    xml.writeAttribute("space-after", "4mm");
    xml.writeCharacters(caption);
    xml.writeEndElement();

    xml.writeStartElement(FO, "block");
    xml.writeStartElement(FO, "external-graphic");
    xml.writeAttribute("src", "url(" + PrintedFormImageUris.uri(renderNonce, imageKey) + ")");
    // The printable area of the body region, not the sheet: scale-down-to-fit against it keeps a
    // portrait tall and a signature wide without either overflowing, and never enlarges a small
    // image past its own resolution.
    xml.writeAttribute("width", "100%");
    xml.writeAttribute("height", "220mm");
    xml.writeAttribute("content-width", "scale-down-to-fit");
    xml.writeAttribute("content-height", "scale-down-to-fit");
    xml.writeAttribute("scaling", "uniform");
    xml.writeEndElement();
    xml.writeEndElement();

    xml.writeEndElement();
  }

  /**
   * An attachment page for a salary certificate that is an IMAGE rather than a PDF.
   *
   * <p>This path exists because the commonest certificate is a photographed payslip, not a PDF —
   * {@code OperatorImagePolicy} admits {@code image/jpeg} and {@code image/png} for this kind as
   * well as {@code application/pdf}. An earlier version claimed in a comment that an image
   * certificate "is a normal attachment page written by FOP with the rest" while no such path
   * existed, so an operator who asked for the attachments silently did not get it.
   *
   * <p>A PDF certificate is NOT here: FOP cannot lay an existing PDF page out, so {@code
   * PrintedFormRenderer} appends those pages to the finished document instead.
   */
  private void imageCertificatePage(
      XMLStreamWriter xml, PrintedFormDocument document, String renderNonce)
      throws XMLStreamException {
    PrintedFormDocument.SalaryCertificate certificate = document.salaryCertificate();
    if (certificate == null || certificate.isPdf()) {
      return;
    }
    attachmentPage(xml, CERTIFICATE_CAPTION, CERTIFICATE_IMAGE_KEY, renderNonce);
  }

  private void imageCell(XMLStreamWriter xml, PrintedImage image, int index, String renderNonce)
      throws XMLStreamException {
    cellStart(xml);
    xml.writeStartElement(FO, "block");
    // The artboard's tile: a faint purple ground inside a hairline, which is what separates a
    // present photograph from the white page behind it and gives an absent one something to be
    // absent INSIDE.
    xml.writeAttribute("border", RULE_WIDTH + " solid " + TILE_BORDER);
    xml.writeAttribute("background-color", TILE);
    xml.writeAttribute("padding", "1.2mm");
    // The tile overhangs its neighbours without this, exactly as the section band did.
    indent(xml);
    xml.writeAttribute("text-align", "center");

    // A BLOCK-CONTAINER, because `height` on an fo:block is not honoured by FOP and an earlier
    // version relied on it. The block's height is driven by its content, so a present image made a
    // box ~33mm tall and the «غير متاح» placeholder made one ~20mm tall - in the same row. Measured
    // on the rendered page rather than guessed: the absent cell's caption sat at y=262.2 while its
    // two row-mates' captions sat at y=302.1, a 39.9pt step in a row that is meant to read as three
    // boxes of one size. A block-container's height IS honoured, so both branches occupy the same
    // block-progression extent and display-align centres whichever one is shorter.
    xml.writeStartElement(FO, "block-container");
    xml.writeAttribute("height", IMAGE_BOX_HEIGHT);
    xml.writeAttribute("width", "100%");
    xml.writeAttribute("display-align", "center");
    xml.writeStartElement(FO, "block");

    if (image.isPresent()) {
      // BOTH dimensions are scale-down-to-fit against a declared box, and both halves matter.
      // A fixed content-height with content-width="scale-to-fit" was tried first: FOP warned that
      // "the contents of fo:external-graphic exceed the available area in the inline-progression
      // direction by more than 50 points" and printed a signature wider than its cell, because
      // nothing constrained the width. A signature is the wide one - a stroke across a page, not a
      // portrait - so it is the case that has to fit.
      //
      // scale-down-to-fit rather than scale-to-fit so a SMALL image is never blown up: ticket 04
      // decision 6 caps the signature at about 2x for exactly this reason, and an upscaled
      // ballpoint stroke turns to mush.
      xml.writeStartElement(FO, "external-graphic");
      xml.writeAttribute(
          "src", "url(" + PrintedFormImageUris.uri(renderNonce, keyFor(index)) + ")");
      xml.writeAttribute("width", "100%");
      xml.writeAttribute("height", IMAGE_BOX_HEIGHT);
      xml.writeAttribute("content-width", "scale-down-to-fit");
      xml.writeAttribute("content-height", "scale-down-to-fit");
      xml.writeAttribute("scaling", "uniform");
      xml.writeEndElement();
    } else {
      // No space-before any more: the container's display-align does the centring, and the fixed
      // 14mm it used to carry was what made an absent box a different height from a present one.
      xml.writeStartElement(FO, "inline");
      xml.writeAttribute("font-size", "7pt");
      xml.writeAttribute("color", FAINT);
      xml.writeCharacters(PrintedValue.ABSENT_TEXT);
      xml.writeEndElement();
    }
    xml.writeEndElement();
    xml.writeEndElement();
    xml.writeEndElement();

    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", "6.5pt");
    xml.writeAttribute("font-weight", "bold");
    xml.writeAttribute("space-before", "0.8mm");
    // The caption alone. No origin label: with five distinct captions the tag stopped telling
    // anything apart and started repeating or misattributing - «السجل المدني — السجل المدني» for
    // the registry portrait, «إثبات الحياة — جواز سفر» for the liveness frame. See PrintedImage.
    xml.writeCharacters(image.caption());
    xml.writeEndElement();
    cellEnd(xml);
  }

  /**
   * Which render-scoped key an image is bound under: its POSITION in the document's image list.
   *
   * <p>Derived from the position rather than from the origin, which an earlier version did. Origin
   * is not unique - two images can share one, and {@code imagesOf} builds a map, so the second
   * would silently overwrite the first and the form would print one image twice under two different
   * captions.
   *
   * <p><strong>Today's set COLLIDES, so this is load-bearing rather than prudent.</strong> An
   * earlier version of this note said the three images then on the form "happen not to collide" and
   * called origin-keying a bug that would be found a year later. Since the 2026-09-14 ruling put
   * five images on the form, three of them — the document scan, the portrait off that document and
   * the liveness frame — share one origin on a passport profile. Keying by origin today would print
   * the same image three times under three captions.
   */
  public static String keyFor(int index) {
    return "image-" + index;
  }

  // ---- small FO helpers, so the methods above read as layout rather than as XML plumbing ----

  private void startTable(XMLStreamWriter xml, List<String> columnWidths)
      throws XMLStreamException {
    xml.writeStartElement(FO, "table");
    xml.writeAttribute("table-layout", "fixed");
    xml.writeAttribute("width", "100%");
    for (String width : columnWidths) {
      xml.writeStartElement(FO, "table-column");
      xml.writeAttribute("column-width", width);
      xml.writeEndElement();
    }
    xml.writeStartElement(FO, "table-body");
  }

  private void endTable(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeEndElement();
    xml.writeEndElement();
  }

  private void cellStart(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeStartElement(FO, "table-cell");
    xml.writeAttribute("padding", "0.8mm");
  }

  private void cellEnd(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeEndElement();
  }

  private void block(XMLStreamWriter xml, String text, String size, String color, boolean latinText)
      throws XMLStreamException {
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("font-size", size);
    xml.writeAttribute("color", color);
    if (latinText) {
      latin(xml, text);
    } else {
      xml.writeCharacters(text);
    }
    xml.writeEndElement();
  }

  /**
   * Fixed horizontal space between two items on one line.
   *
   * <p><strong>A run of literal spaces does NOT do this, and the difference is invisible in the
   * source.</strong> XSL-FO's {@code white-space-collapse} defaults to {@code true}, so the four
   * spaces an earlier version wrote between the identity band's metadata items collapsed to ONE and
   * the items ran together as if they were a sentence. Measured on the rendered page, not inferred:
   * the whole metadata line came back from PDFBox as a single text run with single spaces where the
   * separators should be.
   *
   * <p>Both callers that produced that measurement are gone — S9-03 reduced the band to {@link
   * #compactIdentity} and deleted the continuation header — and the trap is not. The compact line
   * separates the name from the two timestamps this way, and {@link #verificationChips} separates
   * the two chips this way, so a literal space would collapse in exactly the same manner.
   *
   * <p>{@code fo:leader} with a fixed {@code leader-length} is the FO spelling of "this much
   * space", and it is not subject to white-space collapsing. A section badge uses the same element
   * with NO length, where the point is the opposite — to expand and push the badge to the far
   * margin. The footer used to do that too and no longer does: three fixed cells put the operator's
   * name in the same place on every sheet, which a stretching leader cannot.
   */
  private void gap(XMLStreamWriter xml) throws XMLStreamException {
    xml.writeStartElement(FO, "leader");
    xml.writeAttribute("leader-pattern", "space");
    xml.writeAttribute("leader-length", "5mm");
    xml.writeEndElement();
  }

  private void rule(XMLStreamWriter xml, String weight, String color) throws XMLStreamException {
    xml.writeStartElement(FO, "block");
    xml.writeAttribute("border-top-width", weight);
    xml.writeAttribute("border-top-style", "solid");
    xml.writeAttribute("border-top-color", color);
    xml.writeAttribute("space-before", "1mm");
    xml.writeEndElement();
  }

  /**
   * A Latin-script value inside the RTL page. See this class's javadoc: {@code embed}, never {@code
   * bidi-override}, and never FSI/PDI.
   */
  private void latin(XMLStreamWriter xml, String text) throws XMLStreamException {
    xml.writeStartElement(FO, "bidi-override");
    xml.writeAttribute("direction", "ltr");
    xml.writeAttribute("unicode-bidi", "embed");
    xml.writeCharacters(text);
    xml.writeEndElement();
  }
}
