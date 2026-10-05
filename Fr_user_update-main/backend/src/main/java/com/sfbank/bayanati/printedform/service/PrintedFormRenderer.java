package com.sfbank.bayanati.printedform.service;

import com.sfbank.bayanati.printedform.config.RenderScopedImages;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.events.Event;
import org.apache.fop.events.model.EventSeverity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * Renders one {@link PrintedFormDocument} to PDF bytes.
 *
 * <p>Render ONCE, into a {@code byte[]}. Ticket 05 decision 4: the same bytes are streamed to the
 * operator and stored, because a browser-side or second render would make the stored file a
 * near-copy nobody can prove matches what the operator held. A stored print cannot be re-derived
 * and compared either — FOP stamps a creation date and a document id, so the render is not
 * byte-reproducible across runs and the stored checksum is the only evidence of what was printed.
 *
 * <p>Plumbing, not logic: every decision about what the form SAYS was taken before a document
 * reached here, and this class only drives FOP. The FO itself is written by {@link
 * FoDocumentWriter}, which is the testable half.
 */
public class PrintedFormRenderer {

  private final FopFactory fopFactory;
  private final FoDocumentWriter foDocumentWriter;

  public PrintedFormRenderer(FopFactory fopFactory, FoDocumentWriter foDocumentWriter) {
    this.fopFactory = fopFactory;
    this.foDocumentWriter = foDocumentWriter;
  }

  /**
   * @return the PDF. The caller stores these bytes and streams these bytes; it does not render
   *     again.
   */
  public byte[] render(PrintedFormDocument document) {
    // The nonce comes back from bind() and goes into every image URI. It is what stops FOP's
    // factory-level image cache - which keys on the URI string and outlives any one render -
    // answering this customer's form with the previous customer's portrait. Found at review and
    // reproduced live before it was fixed; see PrintedFormImageUris.
    String renderNonce = RenderScopedImages.bind(imagesOf(document));
    try {
      byte[] form = layOut(foDocumentWriter.write(document, renderNonce));

      byte[] certificate = appendedCertificate(document);
      if (certificate == null) {
        return form;
      }
      try (PDDocument attached = Loader.loadPDF(certificate)) {
        // THE SECOND PASS, and what it buys. The footer's «من M» was FOP's own
        // page-number-citation-last, which counts only the sheets FOP laid out - so a bundle whose
        // seventh sheet is the customer's payslip said «صفحة 6 من 6» and its last sheet carried
        // nothing at all. Product-owner ruling, 2026-09-14: the certificate keeps its bare sheet,
        // because it is the customer's own document, and the BANK'S pages carry the honest total
        // and say which sheet the payslip is.
        //
        // Neither number exists until the form has been paginated once and the certificate opened,
        // so the FO is written again with both filled in. The first pass is what learns them.
        int formPages = sheetsIn(form);
        int certificatePages = attached.getNumberOfPages();
        FoDocumentWriter.AppendedCertificate appended =
            new FoDocumentWriter.AppendedCertificate(
                formPages + certificatePages, formPages + 1, formPages + certificatePages);

        byte[] numbered = layOut(foDocumentWriter.write(document, renderNonce, appended));

        // The footer sits in a fixed-extent region, so a longer footer line cannot move the body -
        // but a footer that claims «من 7» on a form that just repaginated to a different length
        // would be a wrong number on a finished-looking document, which is the failure mode this
        // whole class is built to refuse. Cheap to check, so checked.
        if (sheetsIn(numbered) != formPages) {
          throw new IOException(
              "numbering the bundle repaginated the form, "
                  + formPages
                  + " sheets became "
                  + sheetsIn(numbered));
        }
        return merge(numbered, attached);
      }
    } catch (Exception renderFailed) {
      // A failed render must not become a blank or partial PDF. Ticket 05 decision 5 puts the
      // store and the stream in ONE transaction precisely so that a print either exists whole and
      // on the audit trail, or does not exist - and that guarantee starts here.
      throw new PrintedFormRenderFailedException(renderFailed);
    } finally {
      // Never skipped: a ThreadLocal on a pooled request thread outlives the request, and a leak
      // here would leave one customer's portrait reachable while the next customer's form renders
      // on that same thread.
      RenderScopedImages.clear();
      // And the OTHER place this customer's face lives, which clearing the ThreadLocal does not
      // reach. FOP's ImageCache sits on the singleton factory and holds both the ImageInfo and the
      // DECODED image in a SoftMapCache, so a portrait stays softly reachable from a process-wide
      // object until the JVM happens to need the memory - long after the print, and outside the
      // artifact store AD-004 exists to keep identity images in. A heap or core dump taken any time
      // after a print would contain it. Measured, not assumed: after a render returned and clear()
      // ran, the cache still held the render's image entries, and a second render made it three.
      //
      // The nonce that closed the cross-customer leak is what makes this unbounded as well as
      // long-lived: every URI is unique, so entries accumulate instead of overwriting.
      //
      // Only images are dropped. Font metrics live elsewhere on the factory and are NOT re-parsed,
      // which is the expensive thing FOP's documentation says to reuse a factory for. The cost is
      // re-decoding the 10 KB logo per render.
      fopFactory.getImageManager().getCache().clearCache();
    }
  }

  /**
   * Records an Apache FOP event that means the document is NOT what was asked for.
   *
   * <p><strong>FOP reports a missing or undecodable resource as an ERROR EVENT, not an
   * exception.</strong> The render completes and returns a finished-looking PDF. The resolver in
   * {@code FopFactoryProvider} throws an {@code IOException} for an image it cannot answer and FOP
   * swallows it, logs {@code "Image not found"} and lays the page out without it — so a truncated
   * portrait prints an EMPTY bordered box rather than the «غير متاح» the absence rule requires, and
   * a certificate stored with the wrong content type prints a blank sheet under the caption «شهادة
   * المرتب». Those bytes then become the print of record. Found at review and reproduced live.
   *
   * <p>Only the event ID is kept. An event's formatted message can carry document content, and this
   * string reaches an exception message and a log.
   */
  private static void refuse(Event event, List<String> refusals) {
    if (EventSeverity.ERROR.equals(event.getSeverity())
        || EventSeverity.FATAL.equals(event.getSeverity())) {
      refusals.add(event.getEventID());
    }
  }

  private static Map<String, byte[]> imagesOf(PrintedFormDocument document) {
    Map<String, byte[]> images = new HashMap<>();
    List<PrintedImage> all = document.images();
    for (int index = 0; index < all.size(); index++) {
      PrintedImage image = all.get(index);
      if (image.isPresent()) {
        // Keyed by POSITION, matching FoDocumentWriter.keyFor(int). Keying by origin instead lets
        // two images of the same origin overwrite each other in this map, and the form then prints
        // one of them twice under two captions.
        images.put(FoDocumentWriter.keyFor(index), image.bytes());
      }
    }
    // An IMAGE salary certificate gets an attachment page like the others, so its bytes need
    // binding too. A PDF one never reaches FOP - it is appended to the finished document instead.
    PrintedFormDocument.SalaryCertificate certificate = document.salaryCertificate();
    if (certificate != null && !certificate.isPdf()) {
      images.put(FoDocumentWriter.CERTIFICATE_IMAGE_KEY, certificate.bytes());
    }
    return images;
  }

  /**
   * The bytes of a PDF salary certificate that will be stapled onto this print, or null.
   *
   * <p>Null covers three ordinary cases and they are not the same thing: the operator said no to
   * the attachments, the profile has no certificate at all, or the certificate is an IMAGE — which
   * is not appended but written by FOP as an ordinary attachment page with the other five, bound
   * under {@link FoDocumentWriter#CERTIFICATE_IMAGE_KEY}. Only a PDF has to be merged, because FOP
   * renders XSL-FO and cannot lay out an existing PDF page. That is the whole reason PDFBox is a
   * main-scope dependency.
   */
  private static byte[] appendedCertificate(PrintedFormDocument document) {
    PrintedFormDocument.SalaryCertificate certificate = document.salaryCertificate();
    if (!document.includeAttachments() || certificate == null || !certificate.isPdf()) {
      return null;
    }
    return certificate.bytes();
  }

  /** One FOP pass: FO in, PDF out, refusing anything FOP could not render whole. */
  private byte[] layOut(String fo) throws Exception {
    ByteArrayOutputStream pdf = new ByteArrayOutputStream();
    Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdf);

    List<String> refusals = new ArrayList<>();
    fop.getUserAgent().getEventBroadcaster().addEventListener(event -> refuse(event, refusals));

    Transformer transformer = secureTransformerFactory().newTransformer();
    transformer.transform(
        new StreamSource(new ByteArrayInputStream(fo.getBytes(StandardCharsets.UTF_8))),
        new SAXResult(fop.getDefaultHandler()));

    if (!refusals.isEmpty()) {
      throw new IOException("Apache FOP could not render part of this form: " + refusals);
    }
    return pdf.toByteArray();
  }

  private static int sheetsIn(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return document.getNumberOfPages();
    }
  }

  /**
   * Staples the certificate's pages onto the end of the finished form.
   *
   * <p><strong>{@code PDFMergerUtility}, not {@code importPage}, and the difference is not
   * stylistic.</strong> {@code PDDocument.importPage} makes a SHALLOW copy: it carries the page's
   * content stream but not resources the page INHERITS from its {@code /Pages} node. That is legal
   * PDF and several generators emit it, and the result is an appended page that renders WHITE — the
   * scan's XObject is simply missing. PDFBox says so in its own log ("inherited resources of source
   * document are not imported to destination page") and the failure was reproduced against this
   * classpath before it was fixed. {@code appendDocument} resolves the inheritance.
   *
   * <p>Nothing is written back to the artifact store, and nothing is written ONTO the certificate.
   * The stored file is untouched and its checksum still describes the bytes the customer uploaded;
   * the sheet that comes out is the customer's own document, unmarked. Which sheet it is gets said
   * on the bank's pages instead — see {@link FoDocumentWriter.AppendedCertificate}.
   *
   * <p>No catch here. A certificate that will not parse — encrypted, truncated — must fail the
   * WHOLE print rather than quietly yield a form without it: the operator asked for the attachments
   * and would have no way to tell they were dropped. {@code render()}'s own catch wraps it once.
   */
  private static byte[] merge(byte[] form, PDDocument attached) throws IOException {
    try (PDDocument printed = Loader.loadPDF(form);
        ByteArrayOutputStream merged = new ByteArrayOutputStream()) {

      stripActiveContent(attached);
      new PDFMergerUtility().appendDocument(printed, attached);
      // Belt and braces on the destination: the strip above is what actually closes it, and this
      // catches anything appendDocument synthesises rather than copies.
      stripCatalogActions(printed.getDocumentCatalog().getCOSObject());
      printed.save(merged);
      return merged.toByteArray();
    }
  }

  /**
   * Takes everything but the PAGES off a customer-supplied PDF before it is merged.
   *
   * <p><strong>{@code appendDocument} copies far more than pages, and the difference is a security
   * one.</strong> Found at review and reproduced live. PDFBox's merger carries the SOURCE
   * document's {@code /OpenAction} onto the destination whenever the destination has none — and a
   * FOP-produced PDF never has one; it clones the source's whole {@code /Names} dictionary, which
   * is where {@code /JavaScript} and {@code /EmbeddedFiles} live; it merges the source's {@code
   * /Info}; and it merges the source's AcroForm fields.
   *
   * <p>So a customer who uploads a payslip carrying document-level JavaScript, an embedded file or
   * an XFA form gets all of it copied into <em>the bank's</em> filed form — the bytes that are
   * stored as a {@code printed_form} artifact and streamed to the operator's browser, under the
   * name of a bank-generated document. That is the certificate's only untrusted input and this is
   * the only place it is opened.
   *
   * <p>What survives is the page content: the content streams, their resources and the images. That
   * is what "print it in a separate paper" asked for, and nothing else on a scanned or exported
   * payslip is load-bearing.
   */
  private static void stripActiveContent(PDDocument attached) {
    stripCatalogActions(attached.getDocumentCatalog().getCOSObject());
    // Emptied rather than left alone: appendDocument merges the source's title, author and subject
    // into the destination, so an untouched /Info would put a customer's chosen document title on
    // the bank's form.
    attached.setDocumentInformation(new PDDocumentInformation());
    for (PDPage page : attached.getPages()) {
      // Page-level additional-actions, and annotations - a Link annotation carries its own action
      // dictionary, so stripping only the catalog would leave a JavaScript link on the page.
      page.getCOSObject().removeItem(ADDITIONAL_ACTIONS);
      page.setAnnotations(List.of());
    }
  }

  private static void stripCatalogActions(COSDictionary catalog) {
    catalog.removeItem(COSName.OPEN_ACTION);
    catalog.removeItem(COSName.NAMES);
    catalog.removeItem(COSName.ACRO_FORM);
    catalog.removeItem(ADDITIONAL_ACTIONS);
  }

  /** {@code /AA}, the additional-actions dictionary, which COSName has no constant for. */
  private static final COSName ADDITIONAL_ACTIONS = COSName.getPDFName("AA");

  /**
   * Research R-2's other half. The FO reaching this transformer was generated by {@link
   * FoDocumentWriter} with escaping, so it is not attacker-controlled markup — but a parser that
   * would resolve an external entity if one appeared is a parser one refactor away from being a
   * problem, and the cost of closing it is three lines.
   *
   * <p>{@code FEATURE_SECURE_PROCESSING} plus empty {@code ACCESS_EXTERNAL_DTD} and {@code
   * ACCESS_EXTERNAL_STYLESHEET} is the JAXP-standard spelling of "resolve nothing off this box".
   */
  private static TransformerFactory secureTransformerFactory() throws Exception {
    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    return factory;
  }

  /** Thrown when FOP could not produce the document. Never caught into an empty PDF. */
  public static class PrintedFormRenderFailedException extends RuntimeException {
    PrintedFormRenderFailedException(Throwable cause) {
      super("could not render the printed update form", cause);
    }
  }
}
