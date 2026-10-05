package com.sfbank.bayanati.printedform.config;

import com.sfbank.bayanati.printedform.service.PrintedFormImageUris;
import java.util.Map;
import java.util.UUID;

/**
 * How the portraits and the signature reach FOP without ever touching a disk.
 *
 * <p><strong>The problem this solves, and why it looks like this.</strong> FOP resolves every
 * {@code fo:external-graphic src} through the resource resolver held by its {@code FopFactory}, and
 * a {@code FopFactory} is a long-lived singleton by design — building one parses and caches font
 * metrics for every embedded face, so FOP's own documentation says to reuse it. The images, by
 * contrast, are different on every single render: they are one customer's face and one customer's
 * signature.
 *
 * <p>FOP 2.11 offers no supported seam for this. {@code FOUserAgent}'s resolver-taking constructor
 * is package-private, and {@code fopFactory.newFOUserAgent()} hands back one wired to the factory's
 * own resolver. The alternatives were weighed:
 *
 * <ul>
 *   <li><b>A FopFactory per render</b> — correct, and rejected. It re-parses font metrics per
 *       document to work around a missing setter.
 *   <li><b>Writing the images to temp files</b> — rejected outright. It would put an unencrypted
 *       copy of a customer's face and signature on the container's filesystem, outside the artifact
 *       store, with no purge reaching it. That is precisely the class of object AD-004 exists to
 *       keep in one place.
 *   <li><b>{@code data:} URIs</b> — not resolvable either. {@code data:} is no more a JDK URL
 *       scheme than {@code classpath:} is, so it would need this same mechanism anyway, and it
 *       would additionally inflate every image by a third inside the FO document.
 * </ul>
 *
 * <p>So: a thread-scoped registry, bound immediately before the transform and cleared in a {@code
 * finally}. A FOP render is synchronous on the calling thread from {@code transform()} to return,
 * which is what makes this sound rather than merely convenient — the resolver is called back on the
 * same thread that bound the map.
 *
 * <h2>The thread scope is the smaller half of the guarantee</h2>
 *
 * <p>Clearing the {@code ThreadLocal} is necessary — a pooled request thread outlives the request,
 * so a leak would leave one customer's portrait reachable while the next customer's form renders on
 * that same thread — but on its own it is NOT sufficient, and an earlier version of this class
 * claimed otherwise. FOP caches decoded images on the factory, keyed by URI, so a constant URI is
 * answered from that cache without the resolver ever being consulted. The cross-customer leak lived
 * one layer above this ThreadLocal and was invisible to it. {@link PrintedFormImageUris} carries
 * the nonce that closes it, and the reasoning.
 */
public final class RenderScopedImages {

  private static final ThreadLocal<Map<String, byte[]>> BOUND = new ThreadLocal<>();

  private RenderScopedImages() {}

  /**
   * Binds the images for one render on this thread.
   *
   * @return the nonce this render's URIs must carry. Always paired with {@link #clear()} in a
   *     {@code finally}.
   */
  public static String bind(Map<String, byte[]> images) {
    BOUND.set(Map.copyOf(images));
    return UUID.randomUUID().toString();
  }

  /** Releases them. Called from a {@code finally}, never skipped. */
  public static void clear() {
    BOUND.remove();
  }

  /**
   * @return the bytes registered under {@code key} on this thread, or null. Null reaches FOP as an
   *     unresolvable resource, which is the honest answer: an image the assembler did not supply
   *     should not silently become a blank box the reader mistakes for "the registry sent nothing".
   *     The form says that in words instead, with {@code PrintedImage.absent}.
   */
  public static byte[] lookUp(String key) {
    Map<String, byte[]> bound = BOUND.get();
    return bound == null ? null : bound.get(key);
  }
}
