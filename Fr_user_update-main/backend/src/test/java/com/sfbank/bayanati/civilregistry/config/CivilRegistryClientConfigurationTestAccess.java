package com.sfbank.bayanati.civilregistry.config;

import com.sfbank.bayanati.civilregistry.http.CivilRegistryHttpProperties;
import org.springframework.web.client.RestClient;

/**
 * Lets a test in another package reach {@code CivilRegistryClientConfiguration.restClient}, which
 * is package-private on purpose (it is not part of the configuration's public surface). Test code
 * only.
 */
public final class CivilRegistryClientConfigurationTestAccess {

  private CivilRegistryClientConfigurationTestAccess() {}

  /** The exact {@link RestClient} the {@code http} selection would run on, timeouts included. */
  public static RestClient restClient(CivilRegistryHttpProperties properties) {
    return CivilRegistryClientConfiguration.restClient(properties);
  }
}
