package com.sfbank.bayanati.printedform.domain;

import java.util.Objects;

/**
 * One verification chip on the approved page 1 — «التحقق الحي — ناجح» and «تحقق MRZ — صحيح», the
 * only two AD-022 ruling 3 permits on paper.
 *
 * <p><strong>The wording is decided here, not in the writer.</strong> The chip arrives as finished
 * text and a tone, so {@link com.sfbank.bayanati.printedform.service.FoDocumentWriter} draws a box
 * and never asks what a face result means. That is the same split the rest of this package keeps:
 * the assembler decides WHAT is printed, the writer only how.
 *
 * <p><strong>Neither chip may ever carry a figure.</strong> AD-022 ruling 3 removed the face-match
 * display entirely — the match is still run, stored and audited, and is simply not on the page. The
 * liveness chip is derived from the mere PRESENCE of a face result, never from {@code match},
 * {@code matchLevel}, {@code thresholdApplied} or {@code passed}. {@code passed} is especially
 * tempting and especially wrong: V0008 defines it as a GENERATED column, {@code match AND
 * match_level >= threshold_applied}, so it is the face-match verdict wearing a name that reads like
 * liveness. Printing it would breach the ruling while looking like it honoured it.
 */
public record PrintedChip(String text, Tone tone) {

  /**
   * How the chip is drawn. Colour only — an outline and its ink, never a fill, because the approved
   * artboards set these as outline chips beside a page of hairline rules.
   */
  public enum Tone {
    /** The liveness pass, and the only green on the form. */
    GOOD,
    /** Stated but unremarkable: a valid MRZ, or a check the profile never reached. */
    NEUTRAL,
    /** A check that ran and failed. */
    BAD
  }

  public PrintedChip {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(tone, "tone");
  }

  public static PrintedChip good(String text) {
    return new PrintedChip(text, Tone.GOOD);
  }

  public static PrintedChip neutral(String text) {
    return new PrintedChip(text, Tone.NEUTRAL);
  }

  public static PrintedChip bad(String text) {
    return new PrintedChip(text, Tone.BAD);
  }
}
