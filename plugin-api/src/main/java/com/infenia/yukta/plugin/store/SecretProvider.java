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

  /**
   * Resolve a secret for a caller, so a provider can scope secrets per tenant or session. The
   * default ignores the caller and returns the global secret, so providers written against {@link
   * #getSecret(String)} keep working.
   *
   * @param key the secret key (e.g. from decrypted:prefix)
   * @param context who asks; never null
   * @return a Mono containing the decrypted secret, empty if there is none
   */
  default Mono<String> getSecret(final String key, final SecretContext context) {
    return getSecret(key);
  }
}
