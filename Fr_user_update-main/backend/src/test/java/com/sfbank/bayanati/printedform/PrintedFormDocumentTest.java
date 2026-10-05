package com.sfbank.bayanati.printedform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import org.junit.jupiter.api.Test;

/**
 * The form's fixed strings, where getting one subtly wrong would not fail anything else.
 *
 * <p>Plain JUnit, no Spring context and no renderer: {@code domain} is business logic by the
 * package rule, and these are assertions about values, not about rendering.
 */
class PrintedFormDocumentTest {

  /**
   * ARABIC TATWEEL, U+0640. Invisible as a glyph in most editors and indistinguishable from
   * ordinary letter-stretching in a proportional font, which is exactly why it needs a test.
   */
  private static final char TATWEEL = 'ـ';

  @Test
  void theAppNameCarriesTheSplashScreensTatweelSpelling() {
    // WHY THIS IS ASSERTED ON CODEPOINTS rather than against a literal. «بيــانــاتــي» and
    // «بياناتي» look nearly identical in a diff, render identically to anyone not looking for the
    // elongation, and compare unequal. The form is printing the app's MARK, and the mark is the
    // elongated spelling the splash screen sets (launch_screen.dart, _appName) -- a well-meaning
    // tidy-up to the "correct" spelling would silently stop it matching the app, and no rendering
    // test would notice, because both spellings render perfectly well.
    String appName = PrintedFormDocument.APP_NAME_AR;

    assertThat(appName.codePoints().toArray())
        .as("«بياناتي» with two tatweels after each of ي، ن، ت -- the splash screen's own string")
        .containsExactly(
            0x0628, 0x064A, 0x0640, 0x0640, 0x0627, 0x0646, 0x0640, 0x0640, 0x0627, 0x062A, 0x0640,
            0x0640, 0x064A);

    // Stated a second way, because the array above is hard to read and easy to "fix" wrongly: the
    // decoration is exactly six tatweels, and what remains when they are removed is the plain
    // spelling of the app's name.
    assertThat(appName.chars().filter(c -> c == TATWEEL).count()).isEqualTo(6);
    assertThat(appName.replace(String.valueOf(TATWEEL), "")).isEqualTo("بياناتي");
  }
}
