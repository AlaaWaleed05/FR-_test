package com.sfbank.bayanati.operator.config;

import com.sfbank.bayanati.operator.web.OperatorIdentityArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link OperatorIdentityArgumentResolver} so operator controllers can declare an {@code
 * OperatorIdentity} parameter directly.
 */
@Configuration
public class OperatorWebConfiguration implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new OperatorIdentityArgumentResolver());
  }
}
