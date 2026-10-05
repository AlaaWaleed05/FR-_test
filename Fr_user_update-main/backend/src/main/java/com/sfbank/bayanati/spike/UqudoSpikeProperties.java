package com.sfbank.bayanati.spike;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** S1-02 spike configuration. Bound only under the {@code uqudo-spike} profile. */
@ConfigurationProperties(prefix = "fru.uqudo.spike")
public record UqudoSpikeProperties(
    String clientId,
    String clientSecret,
    String spikeKey,
    String authUrl,
    String apiBase,
    String jwksUrl,
    String outputDir) {}
