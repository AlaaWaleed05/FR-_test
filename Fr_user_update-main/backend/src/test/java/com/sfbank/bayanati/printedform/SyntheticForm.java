package com.sfbank.bayanati.printedform;

import com.sfbank.bayanati.printedform.domain.FieldOrigin;
import com.sfbank.bayanati.printedform.domain.PrintedChip;
import com.sfbank.bayanati.printedform.domain.PrintedField;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedImage;
import com.sfbank.bayanati.printedform.domain.PrintedSection;
import com.sfbank.bayanati.printedform.domain.PrintedValue;
import java.util.ArrayList;
import java.util.List;

/**
 * A whole printed form, invented end to end, for tests and for the visual proof.
 *
 * <p><strong>Every value here is synthetic and none resembles a real one</strong> (CLAUDE.md: no
 * real customer data, no live account numbers, no identity-document images in the repo, in
 * fixtures, or in session reports). The name is a placeholder, the account number is outside the
 * seeded stub range, the national and document numbers are patterns of zeros, and the two
 * "portraits" are generated rectangles rather than photographs of anybody.
 *
 * <p><strong>This fixture's nine sections are the OLD order and are not the build target.</strong>
 * It used to say that order was binding and that the artboards must not be followed; the product
 * owner reversed that on 2026-09-16 and the approved design over-rules the paper form's running
 * order (AD-021, AD-022). The nine sections survive here only because this fixture exercises the
 * RENDERER, which lays out whatever sections it is given — the three-section shape is the
 * assembler's to build, and {@link #withApprovedHeadings()} is the fixture that holds the renderer
 * to the approved heading kinds.
 */
final class SyntheticForm {

  private SyntheticForm() {}

  static final String NAME = "محمد أحمد الطيب عبدالله";
  static final String REFERENCE = "SFB-000000777";
  static final String ACCOUNT = "0000009999";
  static final String PHONE = "+249912345678";

  /**
   * The operator who printed the sheet — named in the page footer since S9-03, and named by
   * USERNAME since AD-022 (n) closed BL-163 on 2026-09-18.
   *
   * <p>This fixture held «مشغّل تجريبي» until then, and that is part of why no test caught BL-163.
   * It was wrong twice over: an Arabic DISPLAY NAME where the service passes a username, and short
   * enough that the footer's middle third fitted whatever it was given. Production passed a
   * 36-character UUID and wrapped. A realistic username is the point of this value — it is the
   * artboard's own placeholder, ASCII as {@code FoDocumentWriter.latin}'s LTR override requires.
   */
  static final String PRINTED_BY = "faheem.operator";

  /** The header's «التاريخ», date only. The same moment as the identity line's print time. */
  static final String PRINTED_ON = "14/09/2026";

  static final String LIVENESS_CHIP = "التحقق الحي — ناجح";

  static final String MRZ_CHIP = "تحقق MRZ — صحيح";

  /**
   * The two verification chips of the approved page 1, both in their passing state.
   *
   * <p>The PASSING pair on purpose: it is the shape almost every printed profile has, and the
   * tri-state wording is exercised where it is decided — in {@code PrintedFormAssemblerTest} —
   * rather than here, where only the drawing is under test.
   */
  static List<PrintedChip> chips() {
    return List.of(PrintedChip.good(LIVENESS_CHIP), PrintedChip.neutral(MRZ_CHIP));
  }

  static PrintedFormDocument document() {
    return document(false);
  }

  /**
   * @param includeAttachments the operator's answer to the one print-time question.
   */
  static PrintedFormDocument document(boolean includeAttachments) {
    return new PrintedFormDocument(
        NAME,
        REFERENCE,
        "06/09/2026 11:01",
        PRINTED_BY,
        "14/09/2026 10:45",
        PRINTED_ON,
        chips(),
        sections(),
        images(),
        includeAttachments,
        includeAttachments ? certificate() : null);
  }

  /**
   * The same form with the liveness frame off the page-1 grid and still in the bundle — what {@code
   * PrintedFormAssembler} now produces for every profile (AD-022).
   */
  static PrintedFormDocument withLivenessOffTheGrid(boolean includeAttachments) {
    List<PrintedImage> images = new ArrayList<>(images());
    // Slot 3 of five. Deliberately NOT the last one: a suppressed image at the END of the list
    // would let a grid that renumbered from zero still pair every tile with the right bytes, and
    // the defect this fixture exists for would not show.
    images.set(3, images.get(3).attachmentOnly());
    return new PrintedFormDocument(
        NAME,
        REFERENCE,
        "06/09/2026 11:01",
        PRINTED_BY,
        "14/09/2026 10:45",
        PRINTED_ON,
        chips(),
        sections(),
        images,
        includeAttachments,
        includeAttachments ? certificate() : null);
  }

  /**
   * A cut-down form in the approved SHAPE: a badged section, a sub-heading, and a section that
   * starts a fresh page. Not the real three sections — those are the assembler's to build — only
   * enough of each heading kind for the renderer to be held to it.
   */
  static PrintedFormDocument withApprovedHeadings() {
    List<PrintedSection> sections =
        List.of(
            PrintedSection.badged(
                "١ — بيانات الهوية",
                "من السجل المدني",
                List.of(
                    PrintedField.single(
                        5,
                        "الاسم بالعربي",
                        PrintedValue.arabic(NAME, FieldOrigin.CIVIL_REGISTRY)))),
            PrintedSection.fullWidth("٣ — البيانات المُقدَّمة من العميل", List.of())
                .startingNewPage(),
            PrintedSection.subHeading(
                "قنوات الاتصال",
                List.of(
                    PrintedField.single(
                        25, "التلفون", PrintedValue.latin(PHONE, FieldOrigin.CUSTOMER)))));
    return new PrintedFormDocument(
        NAME,
        REFERENCE,
        "06/09/2026 11:01",
        PRINTED_BY,
        "14/09/2026 10:45",
        PRINTED_ON,
        chips(),
        sections,
        List.of(),
        false,
        null);
  }

  /** The same document with a caller-supplied section list, for heading-shape assertions. */
  static PrintedFormDocument withSections(List<PrintedSection> sections) {
    return withSections(sections, List.of());
  }

  /** Sections and images together, for the assertions about where the grid lands. */
  static PrintedFormDocument withSections(
      List<PrintedSection> sections, List<PrintedImage> images) {
    return new PrintedFormDocument(
        NAME,
        REFERENCE,
        "06/09/2026 11:01",
        PRINTED_BY,
        "14/09/2026 10:45",
        PRINTED_ON,
        chips(),
        sections,
        images,
        false,
        null);
  }

  private static List<PrintedSection> sections() {
    List<PrintedSection> sections = new ArrayList<>();

    sections.add(
        PrintedSection.twoColumn(
            "بيانات الاستمارة",
            List.of(
                PrintedField.single(
                    1, "التاريخ", PrintedValue.latin("06/09/2026", FieldOrigin.SYSTEM)),
                PrintedField.single(
                    2, "الفرع", PrintedValue.arabic("الجمهورية", FieldOrigin.CUSTOMER)),
                // «رقم الحساب البنكي» and «الفرع» above: product-owner corrections, 2026-09-14,
                // and both are the vocabulary the rest of the system already uses - the mobile
                // account-entry screen and the back office's profile page both say «الفرع». The
                // printed form was the outlier.
                PrintedField.single(
                    3, "رقم الحساب البنكي", PrintedValue.latin(ACCOUNT, FieldOrigin.CUSTOMER)))));

    sections.add(
        PrintedSection.twoColumn(
            "البيانات الشخصية",
            List.of(
                // ONE SOURCE EACH, product-owner ruling 2026-09-14. Nationality (4) is customer
                // entry; the Arabic name (5), the English name (6) and the national number (7)
                // are the Civil Registry's. Before this ruling each of these printed BOTH values
                // tagged by origin, which put «السودان» on the page twice and the customer's name
                // twice under two tags - the duplication the product owner objected to.
                PrintedField.single(
                    4, "الجنسية", PrintedValue.arabic("السودان", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    5,
                    "الاسم الكامل بالعربي",
                    PrintedValue.arabic(NAME, FieldOrigin.CIVIL_REGISTRY)),
                PrintedField.single(
                    6,
                    "الاسم الكامل بالانجليزي",
                    PrintedValue.latin("SAMPLE FULL NAME", FieldOrigin.CIVIL_REGISTRY)),
                PrintedField.single(
                    7,
                    "الرقم الوطني",
                    PrintedValue.latin("000-0000-0001", FieldOrigin.CIVIL_REGISTRY)),
                PrintedField.single(
                    8,
                    "اسم الأم",
                    PrintedValue.arabic("فاطمة علي حسن محمد", FieldOrigin.CIVIL_REGISTRY)),
                // Field 9 is the bank's label for SEX and field 10 for ETHNICITY. The form prints
                // the bank's labels, not the literal reading - field-provenance.md's closing note.
                PrintedField.single(
                    9, "النوع", PrintedValue.arabic("ذكر", FieldOrigin.CIVIL_REGISTRY)),
                PrintedField.single(
                        10, "الجنس", PrintedValue.arabic("قبيلة تجريبية", FieldOrigin.CUSTOMER))
                    .markedEdited("مشغّل تجريبي"),
                PrintedField.single(
                    11, "المواطنة", PrintedValue.arabic("السودان", FieldOrigin.CUSTOMER)))));

    sections.add(
        PrintedSection.twoColumn(
            "الحالة الاجتماعية",
            List.of(
                PrintedField.single(
                    12, "الحالة الاجتماعية", PrintedValue.arabic("أعزب", FieldOrigin.CUSTOMER)),
                // ONE spouse row, the one this fixture's sex selects. «أعزب» is an unmarried MAN,
                // so it is 14. Two rows were built here until AD-022 (m) -- this fixture feeds the
                // RENDERER directly rather than going through the assembler, so it would have gone
                // on drawing a form the assembler can no longer produce, and every rendered proof
                // taken from it would have shown a spouse row the real form does not carry.
                PrintedField.single(14, "اسم الزوجة", PrintedValue.absent()),
                PrintedField.single(
                    15, "له أطفال", PrintedValue.arabic("لا", FieldOrigin.CUSTOMER)),
                PrintedField.single(16, "عدد الأطفال", PrintedValue.absent()),
                PrintedField.single(
                        17,
                        "مستوي التعليم",
                        PrintedValue.arabic("دراسات عليا", FieldOrigin.CUSTOMER))
                    .markedEdited("مشغّل تجريبي"),
                // Single-source here BECAUSE THE DOCUMENT SCANNED WAS A PASSPORT, which carries
                // no occupation at all. field-provenance.md marks field 18 S2/S3, but S2 means the
                // national-ID card; a passport never had an answer to withhold, so there is no
                // second row. A profile scanned from a national ID would carry two.
                PrintedField.single(
                    18, "المهنة", PrintedValue.arabic("احصائي", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    19, "النفقات الشهرية", PrintedValue.latin("76,000", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    20,
                    "مصدر الدخل",
                    PrintedValue.arabic("راتب / أجر (رئيسي)", FieldOrigin.CUSTOMER)))));

    sections.add(
        PrintedSection.twoColumn(
            "بيانات الميلاد",
            List.of(
                PrintedField.single(
                    21,
                    "تاريخ الميلاد",
                    PrintedValue.latin("01/01/1990", FieldOrigin.CIVIL_REGISTRY)),
                PrintedField.single(
                    22, "البلد", PrintedValue.arabic("السودان", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    23, "المدينة", PrintedValue.arabic("أم درمان", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    24, "الولاية", PrintedValue.arabic("الخرطوم", FieldOrigin.CUSTOMER)))));

    sections.add(
        PrintedSection.twoColumn(
            "الاتصال",
            List.of(
                PrintedField.single(25, "التلفون", PrintedValue.latin(PHONE, FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    26,
                    "البريد الالكتروني",
                    PrintedValue.latin("sample.new@example.invalid", FieldOrigin.CUSTOMER)))));

    sections.add(
        PrintedSection.twoColumn(
            "عنوان العمل",
            List.of(
                PrintedField.single(
                    27,
                    "جهة العمل",
                    PrintedValue.arabic("شركة تجريبية للتجارة", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    28, "البلد", PrintedValue.arabic("السودان", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    29, "الولاية", PrintedValue.arabic("الخرطوم", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    30, "المحافظة", PrintedValue.arabic("أم درمان", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    31, "المنطقة", PrintedValue.arabic("الثورة", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    32, "المدينة", PrintedValue.arabic("المهدية", FieldOrigin.CUSTOMER)),
                PrintedField.single(33, "الشارع", PrintedValue.latin("8", FieldOrigin.CUSTOMER)),
                PrintedField.single(34, "المربع", PrintedValue.absent()))));

    sections.add(
        PrintedSection.twoColumn(
            "عنوان السكن",
            List.of(
                // 35-41 ARE ALL CUSTOMER ENTRY, and the Civil Registry's address string is no
                // longer on the form at all. Product-owner ruling, 2026-09-14, confirmed when the
                // consequence was put to them explicitly: an earlier round kept the registry's one
                // undecomposed string on field 35 so it appeared somewhere. The registry's address
                // is where it believes the customer lived; refreshing it is the entire purpose of
                // the campaign, so the form shows what the customer says now.
                PrintedField.single(
                    35, "البلد", PrintedValue.arabic("السودان", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    36, "الولاية", PrintedValue.arabic("الخرطوم", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    37, "المحافظة", PrintedValue.arabic("أم درمان", FieldOrigin.CUSTOMER)),
                PrintedField.single(38, "المنطقة", PrintedValue.absent()),
                PrintedField.single(
                    39, "المدينة", PrintedValue.arabic("أم درمان", FieldOrigin.CUSTOMER)),
                PrintedField.single(40, "الشارع", PrintedValue.absent()),
                PrintedField.single(41, "المربع", PrintedValue.absent()),
                PrintedField.single(42, "رقم المنزل", PrintedValue.absent()))));

    sections.add(
        PrintedSection.twoColumn(
            "بيانات الهوية",
            List.of(
                PrintedField.single(
                    43, "نوع الهوية", PrintedValue.arabic("جواز سفر", FieldOrigin.CUSTOMER)),
                PrintedField.single(
                    44, "رقم الهوية", PrintedValue.latin("P00000001", FieldOrigin.PASSPORT)),
                PrintedField.single(
                    45, "تاريخ الإصدار", PrintedValue.latin("01/01/2021", FieldOrigin.PASSPORT)),
                PrintedField.single(
                    46, "مكان الإصدار", PrintedValue.arabic("أم درمان", FieldOrigin.PASSPORT)),
                PrintedField.single(
                    47, "تاريخ الصلاحية", PrintedValue.latin("01/01/2031", FieldOrigin.PASSPORT)),
                PrintedField.single(
                    48, "بلد الإصدار", PrintedValue.latin("SDN", FieldOrigin.PASSPORT)))));

    // FIELD 51 («مستندات الهوية») IS NOT ON THE FORM. Product-owner ruling, 2026-09-14.
    // field-provenance.md already marks it "Not collected" - there is no separate upload of a
    // national ID or passport, because the Uqudo scan satisfies them - so the row could only ever
    // have said that nothing was collected. A filing document does not carry a line whose whole
    // content is the absence of a thing nobody asks for. This narrows ticket 04 decision 1's "all
    // 54 fields" to the 53 that have something to say.
    sections.add(
        PrintedSection.twoColumn(
            "التوقيع والمرفقات",
            List.of(
                PrintedField.single(
                    49, "التوقيع", PrintedValue.arabic("مرفق", FieldOrigin.CUSTOMER)),
                // Field 50 is on the form as a FIELD saying whether a certificate exists. The
                // certificate ITSELF is appended to this same PDF when the operator asks for the
                // attachments - product-owner ruling 2026-09-14, which reversed ticket 05 decision
                // 9's "two separate documents". An earlier version of this comment still asserted
                // the old rule after the reversal shipped.
                PrintedField.single(
                    50, "شهادة مرتب", PrintedValue.arabic("مرفقة", FieldOrigin.CUSTOMER)))));

    return sections;
  }

  /**
   * The FIVE images, product-owner ruling 2026-09-14: the identity document's front page, the
   * portrait off that document, the Civil Registry's photograph, the liveness frame and the
   * signature.
   *
   * <p>Captions are the back office's own ({@code backoffice/src/profiles/artifactTiles.ts}), so an
   * operator reads the same words on screen and on paper. One is deliberately absent, which
   * exercises the «غير متاح» box on the form AND the rule that an absent image gets no attachment
   * page — the ordinary case on a manually completed profile, which has no artifacts at all.
   */
  /**
   * The five captions, in the order the form lays them out: the top row is the first three and the
   * second row the last two plus a pad. Named so a test can say "the captions of the first image
   * row" without restating the fixture's own choice of wording.
   */
  static final List<String> IMAGE_CAPTIONS =
      List.of("وثيقة الهوية", "صورة الوثيقة", "السجل المدني", "إثبات الحياة", "التوقيع");

  private static List<PrintedImage> images() {
    return List.of(
        PrintedImage.documentScan(IMAGE_CAPTIONS.get(0), FieldOrigin.PASSPORT, swatch(150, 110)),
        PrintedImage.portrait(IMAGE_CAPTIONS.get(1), FieldOrigin.PASSPORT, swatch(120, 150)),
        // Absent on purpose, and it is the THIRD - so the top row is the mixed case: two boxes
        // holding an image and one holding «غير متاح», which is what has to come out one height.
        PrintedImage.absent(IMAGE_CAPTIONS.get(2), FieldOrigin.CIVIL_REGISTRY),
        PrintedImage.portrait(IMAGE_CAPTIONS.get(3), FieldOrigin.PASSPORT, swatch(130, 130)),
        PrintedImage.signature(IMAGE_CAPTIONS.get(4), swatch(300, 110)));
  }

  /** The same document with a specific certificate, for the two certificate paths. */
  static PrintedFormDocument withCertificate(PrintedFormDocument.SalaryCertificate certificate) {
    return withCertificate(certificate, true);
  }

  /**
   * @param includeAttachments the operator's answer. <strong>The {@code false} case needs its own
   *     fixture and did not have one</strong>: {@code document(includeAttachments)} ties the
   *     certificate to the answer, so no fixture could produce "the profile HAS a certificate and
   *     the operator said no" — the case the {@code includeAttachments} guards in {@code
   *     PrintedFormRenderer} and {@code FoDocumentWriter} exist for, and the privacy-relevant
   *     direction, since failing it prints a customer's pay document onto a bundle nobody asked to
   *     include it in.
   */
  static PrintedFormDocument withCertificate(
      PrintedFormDocument.SalaryCertificate certificate, boolean includeAttachments) {
    PrintedFormDocument base = document(includeAttachments);
    return new PrintedFormDocument(
        base.customerName(),
        base.referenceNumber(),
        base.submittedAt(),
        base.printedBy(),
        base.printedAt(),
        base.printedOn(),
        base.verificationChips(),
        base.sections(),
        base.images(),
        includeAttachments,
        certificate);
  }

  /**
   * The same document with a different name in the footer.
   *
   * <p>Exists for BL-163's instrument check: the renderer is handed a string and has no opinion
   * about where it came from, so the only way to show the measurement still detects a wrapped
   * footer is to hand it the 36-character identifier that wrapped one.
   */
  static PrintedFormDocument printedBy(PrintedFormDocument base, String printedBy) {
    return new PrintedFormDocument(
        base.customerName(),
        base.referenceNumber(),
        base.submittedAt(),
        printedBy,
        base.printedAt(),
        base.printedOn(),
        base.verificationChips(),
        base.sections(),
        base.images(),
        base.includeAttachments(),
        base.salaryCertificate());
  }

  /** The dimensions of {@link #imageCertificate()}'s swatch, so a test can find it in a PDF. */
  static final int CERTIFICATE_IMAGE_WIDTH = 200;

  static final int CERTIFICATE_IMAGE_HEIGHT = 280;

  /**
   * A certificate PDF carrying the document-level machinery a real one never needs and a hostile
   * one would: an {@code /OpenAction} that runs JavaScript when the file is opened, a {@code
   * /Names} {@code /JavaScript} tree, and document info naming the customer's own title and author.
   *
   * <p>Not a hypothetical shape. {@code PDFMergerUtility.appendDocument} copies all three onto the
   * destination — the source's {@code /OpenAction} whenever the destination has none, and a
   * FOP-produced PDF never has one — so before this was closed, a payslip like this put its script
   * into the bank's own filed form.
   */
  static PrintedFormDocument.SalaryCertificate certificateWithActiveContent() {
    try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
      pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());

      org.apache.pdfbox.cos.COSDictionary javaScript = new org.apache.pdfbox.cos.COSDictionary();
      javaScript.setItem(
          org.apache.pdfbox.cos.COSName.S, org.apache.pdfbox.cos.COSName.getPDFName("JavaScript"));
      javaScript.setString(
          org.apache.pdfbox.cos.COSName.getPDFName("JS"), "app.alert('customer script ran');");

      org.apache.pdfbox.cos.COSDictionary catalog = pdf.getDocumentCatalog().getCOSObject();
      catalog.setItem(org.apache.pdfbox.cos.COSName.OPEN_ACTION, javaScript);

      org.apache.pdfbox.cos.COSDictionary names = new org.apache.pdfbox.cos.COSDictionary();
      names.setItem(
          org.apache.pdfbox.cos.COSName.JAVA_SCRIPT, new org.apache.pdfbox.cos.COSArray());
      catalog.setItem(org.apache.pdfbox.cos.COSName.NAMES, names);

      pdf.getDocumentInformation().setTitle(HOSTILE_TITLE);
      pdf.getDocumentInformation().setAuthor(HOSTILE_TITLE);

      pdf.save(bytes);
      return new PrintedFormDocument.SalaryCertificate(
          bytes.toByteArray(), PrintedFormDocument.SalaryCertificate.PDF);
    } catch (java.io.IOException cannotBuild) {
      throw new IllegalStateException(cannotBuild);
    }
  }

  /** What the hostile certificate calls itself. Must not appear on the bank's document. */
  static final String HOSTILE_TITLE = "CUSTOMER SUPPLIED TITLE";

  /**
   * A certificate that is a PHOTOGRAPH rather than a PDF — the commonest real case, since the
   * mobile picker offers the camera and {@code OperatorImagePolicy} admits jpeg and png for this
   * kind. Synthetic swatch; nobody's payslip.
   */
  static PrintedFormDocument.SalaryCertificate imageCertificate() {
    return new PrintedFormDocument.SalaryCertificate(
        swatch(CERTIFICATE_IMAGE_WIDTH, CERTIFICATE_IMAGE_HEIGHT), "image/png");
  }

  /**
   * A salary certificate that is a real, minimal PDF — the case that forced PDFBox into main scope,
   * since FOP cannot lay an existing PDF page out and it has to be appended instead. Synthetic: one
   * blank page, no content, nobody's pay document.
   */
  static PrintedFormDocument.SalaryCertificate certificate() {
    try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      pdf.addPage(page);

      // THE RESOURCES LIVE ON THE PAGE TREE, NOT ON THE PAGE, and that is the whole point of this
      // fixture. It is legal PDF and several generators emit it. PDDocument.importPage copies a
      // page SHALLOWLY and leaves inherited resources behind, so a certificate shaped like this
      // appended that way renders WHITE - the image is simply missing. Proven live before the
      // renderer moved to PDFMergerUtility. A blank page would not have caught it.
      org.apache.pdfbox.pdmodel.PDResources inherited = new org.apache.pdfbox.pdmodel.PDResources();
      org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject stamp =
          org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(
              pdf, swatchImage(60, 60));
      org.apache.pdfbox.cos.COSName name = inherited.add(stamp);
      pdf.getPages()
          .getCOSObject()
          .setItem(org.apache.pdfbox.cos.COSName.RESOURCES, inherited.getCOSObject());

      try (org.apache.pdfbox.pdmodel.PDPageContentStream content =
          new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
        content.drawImage(stamp, 60, 60, 120, 120);
      }
      // The page itself declares NO resources; only the tree above it does.
      page.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.RESOURCES);
      pdf.save(bytes);
      return new PrintedFormDocument.SalaryCertificate(
          bytes.toByteArray(), PrintedFormDocument.SalaryCertificate.PDF);
    } catch (java.io.IOException cannotBuild) {
      throw new IllegalStateException(cannotBuild);
    }
  }

  /**
   * A generated rectangle standing in for an image. NOT a photograph of anybody: CLAUDE.md forbids
   * identity-document images in the repo and in fixtures, and a checked-in face would be one
   * whether or not it belonged to a customer.
   */
  /** A real, decodable PNG — package-private so the assembler's visual proof can use it. */
  static byte[] swatch(int width, int height) {
    java.awt.image.BufferedImage image = swatchImage(width, height);
    try {
      java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "PNG", png);
      return png.toByteArray();
    } catch (java.io.IOException cannotWriteSwatch) {
      throw new IllegalStateException(cannotWriteSwatch);
    }
  }

  private static java.awt.image.BufferedImage swatchImage(int width, int height) {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    graphics.setColor(new java.awt.Color(0xEE, 0xF2, 0xF7));
    graphics.fillRect(0, 0, width, height);
    graphics.setColor(new java.awt.Color(0x59, 0x80, 0xA6));
    graphics.drawRect(4, 4, width - 9, height - 9);
    graphics.drawLine(4, 4, width - 5, height - 5);
    graphics.drawLine(4, height - 5, width - 5, 4);
    graphics.dispose();
    return image;
  }
}
