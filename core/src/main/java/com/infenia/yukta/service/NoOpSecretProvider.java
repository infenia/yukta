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
