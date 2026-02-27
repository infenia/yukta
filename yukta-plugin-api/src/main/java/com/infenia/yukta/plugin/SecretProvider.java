/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin;

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
