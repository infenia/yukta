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
package com.infenia.jagratha.service;

import com.infenia.jagratha.plugin.SecretProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Default implementation of SecretProvider that does nothing. */
@Slf4j
@Service
public class NoOpSecretProvider implements SecretProvider {

  /** Default constructor. */
  public NoOpSecretProvider() {
    super();
  }

  @Override
  public Mono<String> getSecret(final String key) {
    if (log.isWarnEnabled()) {
      log.warn("Secret requested for key '{}' but no SecretProvider is configured.", key);
    }
    return Mono.empty();
  }
}
