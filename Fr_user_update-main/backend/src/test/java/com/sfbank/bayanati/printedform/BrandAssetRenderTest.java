package com.sfbank.bayanati.printedform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sfbank.bayanati.printedform.config.FopFactoryProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.apache.fop.events.model.EventSeverity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

/**
 * Proves FOP can place the form's brand assets, at the size and on the pages the design needs.
 *
 * <p>Written at S9-03 against the two AZ assets, BEFORE the form was rebuilt around them. The
 * header half was re-pointed at S9-06, when AD-022 (h) replaced the AZ lockup with the bank's
 * roundel and the Arabic wordmark: this test builds its OWN FO rather than calling the writer, so
 * nothing about it would have failed when the form stopped drawing the lockup — it would simply
 * have gone on proving the placement of an image the form no longer uses.
 *
 * <p><strong>The watermark half is gone at S9-06.</strong> AD-022 (j) took the AZ watermark off the
 * page, so this no longer renders a region background or the fox: extension that sized it. What the
 * S9-03 measurement established is recorded on {@code FoDocumentWriter.pageMaster} instead, where a
 * future background would need it: a plain {@code background-image} draws at the asset's intrinsic
 * PHYSICAL size, the {@code fox:} pair is the only way to size it, and a positioned {@code
 * block-container} paints ON TOP of the body rather than behind it.
 *
 * <p><strong>Why a brand asset needs measuring rather than assuming.</strong> A URI the custom
 * resolver refuses does not degrade: {@link
 * com.sfbank.bayanati.printedform.config.FopFactoryProvider}'s resolver throws, FOP swallows that
 * into an ERROR event, and {@code PrintedFormRenderer} turns any ERROR event into a thrown {@code
 * IOException}. A wrong brand URI therefore fails the whole render rather than printing a page
 * without its mark.
 *
 * <p><strong>Placement is read from each page's content stream, not from its resources.</strong>
 * FOP emits one {@code /Resources} dictionary for the whole document and points every page at it,
 * so asking a page which images it *has* returns the document's union and answers nothing about
 * what that page DRAWS. Reading the {@code cm} / {@code Do} pair is what makes "on every page" and
 * "at this size" real assertions rather than ones that pass on a document where a header mark
 * appears once.
 *
 * <p>The assets are an awkward shape on purpose, and still are: {@code sfb-logo-circle.png} is an
 * 8-bit PALETTE PNG with a {@code tRNS} chunk and {@code sfb-wordmark-ar-navy.png} is RGBA, and FOP
 * embeds either as the picture PLUS a separate soft-mask XObject of identical dimensions. Only the
 * picture is drawn, which is another reason to count draws rather than resources — and the reason
 * an XObject COUNT is not the same number as an image count.
 *
 * <p>No Arabic and no customer data: this test is about image placement only.
 */
class BrandAssetRenderTest {

  /**
   * The two marks the header draws since S9-06, at the sizes the form really uses — 9mm and 4mm,
   * not a size invented for this test. Re-pointed from {@code az-lockup.png}, which AD-022 (h) took
   * off the form: a placement proof for an image nothing places proves nothing.
   */
  private static final String LOGO = "classpath:brand/sfb-logo-circle.png";

  private static final String LOGO_HEIGHT = "9mm";

  private static final String WORDMARK = "classpath:brand/sfb-wordmark-ar-navy.png";

  private static final String WORDMARK_HEIGHT = "4mm";

  /** Six numbers, the literal {@code cm}, then a named XObject draw. */
  private static final Pattern PLACEMENT =
      Pattern.compile(
          "([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)\\s+([-0-9.]+)"
              + "\\s+cm\\s*/([A-Za-z0-9]+)\\s+Do");

  @Test
  void fopDrawsBothBrandMarksInTheHeaderOfEveryPage() throws Exception {
    List<String> events = new ArrayList<>();
    byte[] pdf = render(brandFo(), events);

    assertThat(errors(events))
        .as("a refused or undecodable brand URI surfaces as a FOP ERROR event, not a blank page")
        .isEmpty();

    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isEqualTo(2);

      List<String> firstPage = draws(document.getPage(0));
      List<String> secondPage = draws(document.getPage(1));

      // Both marks on BOTH pages, which is what a static-content header buys and what the form
      // relies on -- PrintedFormRendererTest's per-page header guard asserts these same two sizes
      // on the real document.
      assertThat(secondPage)
          .as("the header repeats on every page, marks and all")
          .contains("9x9", "19x4");

      // 9x9: the roundel is SQUARE (192x192) at content-height 9mm. This is the assertion that
      // would catch it being enlarged into the 34mm-header shape the old SFB letterhead had — see
      // FoDocumentWriter.LOGO, and BL-162, which is what an inflated band would break.
      assertThat(firstPage).as("page 1 draws the bank's roundel in its header").contains("9x9");

      // 19x4: the wordmark cropped to its Arabic line is 617x129, ratio 4.78, at content-height
      // 4mm -> 4 * 617/129 = 19.1mm. Wide and short, which is the point of cropping it: the
      // uncropped two-line source is 3.05:1 and would carry Latin text the form does not print.
      assertThat(firstPage).as("page 1 draws the bank's wordmark beside it").contains("19x4");
    }
  }

  /** Each image DRAW on this page, as {@code WIDTHxHEIGHT} in whole millimetres. */
  private static List<String> draws(PDPage page) throws Exception {
    String content;
    try (var in = page.getContents()) {
      content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
    List<String> drawn = new ArrayList<>();
    Matcher matcher = PLACEMENT.matcher(content);
    while (matcher.find()) {
      double width = Math.abs(Double.parseDouble(matcher.group(1))) / 72 * 25.4;
      double height = Math.abs(Double.parseDouble(matcher.group(4))) / 72 * 25.4;
      // Locale.ROOT, because the literals this is compared against are ASCII digits. A JVM
      // defaulting to an Arabic-Indic numbering locale would format these as «٩x٩» and no
      // assertion in this file would match -- a green suite turning red on someone else's machine.
      drawn.add(String.format(Locale.ROOT, "%.0fx%.0f", width, height));
    }
    return drawn;
  }

  /**
   * Severity, not a string match — the same test {@code PrintedFormRenderer.refuse} applies, so
   * this cannot diverge from what the renderer would actually refuse.
   */
  private static List<String> errors(List<String> events) {
    return events.stream()
        .filter(event -> event.startsWith("ERROR ") || event.startsWith("FATAL "))
        .toList();
  }

  /** Two pages, so the header is proved to repeat rather than to land once. */
  private static String brandFo() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
          <fo:layout-master-set>
            <fo:simple-page-master master-name="brand"
                                   page-width="210mm" page-height="297mm"
                                   margin-top="10mm" margin-bottom="10mm"
                                   margin-left="13mm" margin-right="13mm">
              <fo:region-body margin-top="34mm" margin-bottom="14mm"/>
              <fo:region-before region-name="header" extent="34mm"/>
            </fo:simple-page-master>
          </fo:layout-master-set>
          <fo:page-sequence master-reference="brand" id="brand">
            <fo:static-content flow-name="header">
              <fo:block>
                <fo:external-graphic src="url(%s)" content-height="%s"
                                     content-width="scale-to-fit" scaling="uniform"/>
                <fo:external-graphic src="url(%s)" content-height="%s"
                                     content-width="scale-to-fit" scaling="uniform"/>
              </fo:block>
            </fo:static-content>
            <fo:flow flow-name="xsl-region-body">
              <fo:block>page one under the header</fo:block>
              <fo:block break-before="page">page two under the header</fo:block>
            </fo:flow>
          </fo:page-sequence>
        </fo:root>
        """
        .formatted(LOGO, LOGO_HEIGHT, WORDMARK, WORDMARK_HEIGHT);
  }

  private static byte[] render(String fo, List<String> events) throws Exception {
    FopFactory fopFactory = FopFactoryProvider.create();

    ByteArrayOutputStream pdf = new ByteArrayOutputStream();
    Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdf);

    EventListener listener =
        (Event event) -> events.add(severity(event) + " " + EventFormatter.format(event));
    fop.getUserAgent().getEventBroadcaster().addEventListener(listener);

    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.transform(
        new StreamSource(new ByteArrayInputStream(fo.getBytes(StandardCharsets.UTF_8))),
        new SAXResult(fop.getDefaultHandler()));

    return pdf.toByteArray();
  }

  private static String severity(Event event) {
    EventSeverity severity = event.getSeverity();
    if (severity == EventSeverity.FATAL) {
      return "FATAL";
    }
    return severity == EventSeverity.ERROR ? "ERROR" : "OK";
  }
}
