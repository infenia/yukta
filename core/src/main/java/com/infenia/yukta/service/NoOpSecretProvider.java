// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.store.SecretProvider;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Default implementation of SecretProvider that does nothing. */
@Slf4j
@Service
@NoArgsConstructor
public class NoOpSecretProvider implements SecretProvider {

  @Override
  public Mono<String> getSecret(final String key) {
    log.atWarn().log("Secret requested for key '{}' but no SecretProvider is configured.", key);
    return Mono.empty();
  }
}
