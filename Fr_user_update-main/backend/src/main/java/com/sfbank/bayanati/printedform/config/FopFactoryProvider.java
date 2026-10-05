package com.sfbank.bayanati.printedform.config;

import com.sfbank.bayanati.printedform.service.PrintedFormImageUris;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Objects;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.xmlgraphics.io.Resource;
import org.apache.xmlgraphics.io.ResourceResolver;
import org.xml.sax.SAXException;

/**
 * Builds the one {@link FopFactory} the printed form renders through. PLUMBING, deliberately: it
 * owns no decision about what the form says, only how Apache FOP is wired up, so it sits in {@code
 * config} and not in {@code domain}/{@code service} (CLAUDE.md's package rule).
 *
 * <p><strong>Why this class exists at all, rather than {@code
 * FopFactory.newInstance(uri)}.</strong> Found live at the S8-33 acceptance render, and it is the
 * kind of failure that reaches production intact: FOP resolves {@code embed-url} through
 * xmlgraphics-commons' default resource resolver, which is built on {@link java.net.URL}. {@code
 * classpath:} is a Spring scheme, not a JDK one, so plain Java cannot open it. FOP does not fail
 * the render when a font will not resolve — it reports a "Font not found, substituting" EVENT and
 * carries on with a default face. The default face is Times, Times has no Arabic, and the document
 * that comes out is a correctly laid out form whose every Arabic word is a missing-glyph box.
 *
 * <p>A file path instead of a classpath URI would paper over it here and fail in the deployed
 * image, where the fonts live inside the Boot fat jar and there is no such file. So the resolver is
 * the fix, not the URI.
 *
 * <p><strong>Nothing here trusts the network.</strong> The resolver answers {@code classpath:} from
 * this application's own classloader and refuses everything else outright. The renderer's FO is
 * generated in-process and references no external resource, so an unresolvable URI means a mistake
 * in this repository rather than a resource to go and fetch — and refusing is what keeps a stray
 * {@code http:} in a future template from turning the renderer into an outbound HTTP client.
 */
public final class FopFactoryProvider {

  /** Where {@code fop.xconf} and the embedded faces live, both on the classpath. */
  private static final String CONFIGURATION = "fop/fop.xconf";

  private static final String CLASSPATH_SCHEME = "classpath";

  private FopFactoryProvider() {}

  /**
   * The factory, configured from {@code fop/fop.xconf}.
   *
   * <p>FOP's own documentation calls {@code FopFactory} thread-safe and reusable, and building one
   * parses and caches font metrics for every embedded face — so this is called once and the result
   * held, never called per document. A {@code Fop} instance is the per-document object.
   */
  public static FopFactory create() {
    try (InputStream configuration = openClasspath(CONFIGURATION)) {
      if (configuration == null) {
        throw new IllegalStateException(CONFIGURATION + " is not on the classpath");
      }
      // The base URI is only ever used to resolve relative references out of the configuration.
      // Everything this configuration names is absolute and classpath-scoped, so it is a
      // placeholder rather than a location, and it must still be a legal URI.
      return new FopConfParser(configuration, URI.create("classpath:/"), CLASSPATH_RESOLVER)
          .getFopFactoryBuilder()
          .build();
    } catch (IOException | SAXException cannotConfigureFop) {
      // FOPException extends SAXException, so the one alternative covers both.
      // Failing loudly is the point. A renderer that starts with no usable Arabic face produces
      // documents that look finished and say nothing.
      throw new IllegalStateException("could not configure Apache FOP", cannotConfigureFop);
    }
  }

  private static final ResourceResolver CLASSPATH_RESOLVER =
      new ResourceResolver() {

        @Override
        public Resource getResource(URI uri) throws IOException {
          if (PrintedFormImageUris.SCHEME.equals(uri.getScheme())) {
            // One customer's portrait or signature, bound to this thread for this render only.
            // See RenderScopedImages for why the images arrive this way rather than as files, and
            // PrintedFormImageUris for why the nonce in this URI is load-bearing rather than noise.
            byte[] image =
                RenderScopedImages.lookUp(PrintedFormImageUris.keyOf(uri.getSchemeSpecificPart()));
            if (image == null) {
              throw new IOException("no image bound for this render: " + uri);
            }
            return new Resource(new java.io.ByteArrayInputStream(image));
          }
          if (!CLASSPATH_SCHEME.equals(uri.getScheme())) {
            throw new IOException(
                "the printed-form renderer resolves classpath: and "
                    + PrintedFormImageUris.SCHEME
                    + ": URIs only, not "
                    + uri);
          }
          // Both "classpath:fonts/x.ttf" (opaque, path in the scheme-specific part) and
          // "classpath:/fonts/x.ttf" (hierarchical) are spellings people write; accept either
          // rather than making the leading slash a silent difference between a rendered form and
          // a page of boxes.
          String path = uri.isOpaque() ? uri.getSchemeSpecificPart() : uri.getPath();
          InputStream bytes = openClasspath(path.startsWith("/") ? path.substring(1) : path);
          if (bytes == null) {
            throw new IOException("not on the classpath: " + uri);
          }
          return new Resource(bytes);
        }

        @Override
        public OutputStream getOutputStream(URI uri) throws IOException {
          // FOP asks for this only for output formats that write side files. We render to a
          // ByteArrayOutputStream we hand it ourselves, so nothing should ever reach here.
          throw new IOException("the printed-form renderer writes no side files; asked for " + uri);
        }
      };

  private static InputStream openClasspath(String path) {
    ClassLoader loader =
        Objects.requireNonNullElseGet(
            Thread.currentThread().getContextClassLoader(), FopFactoryProvider::classLoader);
    InputStream fromContext = loader.getResourceAsStream(path);
    return fromContext != null ? fromContext : classLoader().getResourceAsStream(path);
  }

  private static ClassLoader classLoader() {
    return FopFactoryProvider.class.getClassLoader();
  }
}
