package com.sfbank.bayanati.printedform.domain;

import java.util.List;
import java.util.Objects;

/**
 * One titled group of fields, in the approved design's three-section order.
 *
 * <p><strong>The nine-section order this type used to describe is over-ruled.</strong> It used to
 * say the form followed {@code field-provenance.md}'s own nine sections, that the artboards
 * departed from that for layout balance, and that where the two disagreed the DECISION governed the
 * renderer. AD-021 then restructured both the screen and the form into three sections, AD-022 made
 * the approved artboards the build target, and on 2026-09-16 the product owner answered the
 * question directly: the recently approved design over-rules the bank paper form's running order.
 * The artboards are the build target now, not layout demonstrations.
 *
 * <p>The three sections are «بيانات الهوية» (the Civil Registry's fields), «التحقق من الهوية» (the
 * scanned document's), and «البيانات المُقدَّمة من العميل», which carries six sub-headings and
 * starts a new page.
 *
 * @param title the Arabic section heading
 * @param badge the small grey qualifier printed at the far end of the heading band — «من السجل
 *     المدني» on section 1, «من وثيقة الهوية والتحقق الحي» on section 2. Null where the heading
 *     carries none, which is every sub-heading and section 3 itself.
 * @param twoColumn whether the section's fields lay out two to a row. Sections 1 and 2 do; section
 *     3 is one column, because its rows are the long free-text ones and a second column would set
 *     them in a column narrower than their content.
 * @param level whether this heading is a section or one of section 3's six sub-headings. Only the
 *     type size, weight and rule differ — a sub-heading is not a nested section and carries no
 *     fields of its own beyond the ones listed on it.
 * @param breakBefore whether this section starts a fresh page. True for section 3 alone, which is
 *     what makes the form the approved TWO pages rather than however many the content runs to.
 */
public record PrintedSection(
    String title,
    String badge,
    List<PrintedField> fields,
    boolean twoColumn,
    PrintedSection.Level level,
    boolean breakBefore) {

  /** A section heading, or one of section 3's sub-headings. */
  public enum Level {
    SECTION,
    SUBSECTION
  }

  public PrintedSection {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(level, "level");
    fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
  }

  /** A two-column section with no badge. */
  public static PrintedSection twoColumn(String title, List<PrintedField> fields) {
    return new PrintedSection(title, null, fields, true, Level.SECTION, false);
  }

  /** A one-column section with no badge. */
  public static PrintedSection fullWidth(String title, List<PrintedField> fields) {
    return new PrintedSection(title, null, fields, false, Level.SECTION, false);
  }

  /** A two-column section carrying the artboard's heading badge — sections 1 and 2. */
  public static PrintedSection badged(String title, String badge, List<PrintedField> fields) {
    return new PrintedSection(title, badge, fields, true, Level.SECTION, false);
  }

  /** One of section 3's six sub-headings: one column, no badge. */
  public static PrintedSection subHeading(String title, List<PrintedField> fields) {
    return new PrintedSection(title, null, fields, false, Level.SUBSECTION, false);
  }

  /** The same section, starting a fresh page. */
  public PrintedSection startingNewPage() {
    return new PrintedSection(title, badge, fields, twoColumn, level, true);
  }

  public boolean isSubHeading() {
    return level == Level.SUBSECTION;
  }
}
