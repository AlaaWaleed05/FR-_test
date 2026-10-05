package com.sfbank.bayanati.printedform.service;

/**
 * The URI vocabulary the FO document uses for an image supplied in memory for one render.
 *
 * <p>Pure, and deliberately here in {@code service} rather than beside the resolver that consumes
 * it: the scheme is part of what the renderer EMITS, and a logic package reaching into an adapter
 * package for a constant is the dependency direction CLAUDE.md's port rule exists to prevent. The
 * resolver in {@code config} reads these; nothing here knows the resolver exists.
 *
 * <h2>Why every URI carries a nonce</h2>
 *
 * <p>This is not decoration, and getting it wrong is not a cosmetic bug. FOP's {@code ImageCache}
 * lives on the {@code FopFactory}, which is a long-lived singleton by design, and it keys both the
 * {@code ImageInfo} and the decoded image on the URI STRING. A URI that is constant per image slot
 * — {@code render-image:portrait-uqudo} — is therefore a cache hit on the second render, and the
 * resolver is never called at all.
 *
 * <p>The consequence, found at review and reproduced live before it was fixed: <strong>the first
 * customer's portrait prints on the second customer's form.</strong> Two renders through one
 * factory, one binding a red portrait and one a green, both produced the red image. A thread-scoped
 * registry does not help — the bytes escape one layer above it, and the ThreadLocal was being
 * cleared correctly the whole time.
 *
 * <p>A fresh nonce per render makes every URI unique, so the cache can never answer for a previous
 * render. It also disarms a second edge of the same mechanism: {@code
 * ImageCache.registerInvalidURI} remembers an unresolvable URI for an hour, so one unbound image
 * under a constant URI would refuse that slot for every form printed in the next hour.
 */
public final class PrintedFormImageUris {

  /** The scheme. Resolved in-process only; it never reaches a network or a filesystem. */
  public static final String SCHEME = "render-image";

  private PrintedFormImageUris() {}

  /**
   * The {@code src} for the image registered under {@code key} in the render identified by nonce.
   */
  public static String uri(String nonce, String key) {
    return SCHEME + ":" + nonce + "/" + key;
  }

  /**
   * The key half of a URI this class produced — everything after the first {@code /}. The nonce is
   * discarded here on purpose: it exists to defeat a cache, not to address anything, and the
   * registry it resolves against is already scoped to the render that bound it.
   */
  public static String keyOf(String schemeSpecificPart) {
    int separator = schemeSpecificPart.indexOf('/');
    return separator < 0 ? schemeSpecificPart : schemeSpecificPart.substring(separator + 1);
  }
}
