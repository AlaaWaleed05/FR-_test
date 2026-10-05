package com.sfbank.bayanati.corebanking.config;

import com.sfbank.bayanati.corebanking.http.CoreBankingHttpProperties;
import org.springframework.web.client.RestClient;

/**
 * Lets a test in another package reach {@code CoreBankingClientConfiguration.restClient}, which is
 * package-private on purpose (it is not part of the configuration's public surface). Test code
 * only.
 */
public final class CoreBankingClientConfigurationTestAccess {

  private CoreBankingClientConfigurationTestAccess() {}

  /** The exact {@link RestClient} the {@code http} selection would run on, timeouts included. */
  public static RestClient restClient(CoreBankingHttpProperties properties) {
    return CoreBankingClientConfiguration.restClient(properties);
  }
}
