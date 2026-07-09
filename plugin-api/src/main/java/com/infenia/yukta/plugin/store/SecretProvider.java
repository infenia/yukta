// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

import reactor.core.publisher.Mono;

/** Interface for fetching encrypted or sensitive values. */
@FunctionalInterface
public interface SecretProvider {
  /**
   * Resolve a secret value.
   *
   * @param key the secret key (e.g. from decrypted:prefix)
   * @return a Mono containing the decrypted secret
   */
  Mono<String> getSecret(String key);
}
