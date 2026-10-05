package com.sfbank.bayanati.printedform.config;

import com.sfbank.bayanati.printedform.service.FoDocumentWriter;
import com.sfbank.bayanati.printedform.service.PrintedFormRenderer;
import org.apache.fop.apps.FopFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the renderer that has existed since S8-33 with nothing calling it.
 *
 * <p>The {@link FopFactory} is a SINGLETON on purpose and it is expensive to build — it parses
 * {@code fop.xconf} and the two Arabic faces. Keeping one is what makes a print cost a layout pass
 * rather than a font-parsing pass, and the two hazards that come with sharing it are both already
 * closed inside {@code PrintedFormRenderer}: image URIs carry a per-render nonce so one customer's
 * portrait cannot be served onto the next customer's form, and the factory's {@code SoftMapCache}
 * is cleared in the same {@code finally} as the thread-scoped registry so no customer's face
 * outlives the render in a shared map (both found and fixed at S8-35).
 *
 * <p>Failing loudly at startup is deliberate: {@code FopFactoryProvider.create()} throws if the
 * configuration or a font will not load, and a renderer that starts with no usable Arabic face
 * produces documents that look finished and say nothing.
 */
@Configuration
public class PrintedFormConfiguration {

  @Bean
  public FopFactory fopFactory() {
    return FopFactoryProvider.create();
  }

  @Bean
  public FoDocumentWriter foDocumentWriter() {
    return new FoDocumentWriter();
  }

  @Bean
  public PrintedFormRenderer printedFormRenderer(
      FopFactory fopFactory, FoDocumentWriter foDocumentWriter) {
    return new PrintedFormRenderer(fopFactory, foDocumentWriter);
  }
}
