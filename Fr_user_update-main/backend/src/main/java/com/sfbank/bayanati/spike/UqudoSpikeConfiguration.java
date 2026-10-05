package com.sfbank.bayanati.spike;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * S1-02 throwaway device spike -- everything in this package exists only under the {@code
 * uqudo-spike} profile and is excluded from the coverage gate. It talks to Uqudo directly and never
 * through the {@code UqudoClient} port; the real adapter is later work (R-034 parser rewrite
 * first).
 */
@Configuration
@Profile("uqudo-spike")
@EnableConfigurationProperties(UqudoSpikeProperties.class)
class UqudoSpikeConfiguration {

  @Bean
  RestClient uqudoSpikeRestClient() {
    return RestClient.builder().build();
  }

  @Bean
  UqudoSpikeStore uqudoSpikeStore(UqudoSpikeProperties properties) {
    return new UqudoSpikeStore(Path.of(properties.outputDir()));
  }

  @Bean
  UqudoSpikeTokenSource uqudoSpikeTokenSource(
      UqudoSpikeProperties properties, RestClient uqudoSpikeRestClient, UqudoSpikeStore store) {
    return new UqudoSpikeTokenSource(properties, uqudoSpikeRestClient, store);
  }

  @Bean
  UqudoSpikeHttp uqudoSpikeHttp(
      UqudoSpikeProperties properties,
      RestClient uqudoSpikeRestClient,
      UqudoSpikeTokenSource tokenSource) {
    return new UqudoSpikeHttp(properties, uqudoSpikeRestClient, tokenSource);
  }

  @Bean
  UqudoSpikeJws uqudoSpikeJws(UqudoSpikeProperties properties) {
    return new UqudoSpikeJws(properties.jwksUrl());
  }
}
