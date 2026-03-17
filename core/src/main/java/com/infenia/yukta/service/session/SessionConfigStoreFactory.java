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
package com.infenia.yukta.service.session;

import com.infenia.yukta.config.SessionConfigProperties;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Factory for creating SessionConfigStore instances based on configuration. Allows runtime
 * selection of store implementation via the yukta.session.store-type property.
 */
@Slf4j
@Component
public class SessionConfigStoreFactory {

  @SuppressWarnings("EI_EXPOSE_REP2")
  private final InMemorySessionConfigStore inMemoryStore;

  private final FileSessionConfigStore fileStore;

  @SuppressWarnings("EI_EXPOSE_REP2")
  private final SessionConfigProperties props;

  /**
   * Constructs the factory with available store implementations.
   *
   * @param inMemoryStore the in-memory store implementation
   * @param objectMapper the JSON object mapper
   * @param props the session configuration properties
   */
  @Autowired
  public SessionConfigStoreFactory(
      final InMemorySessionConfigStore inMemoryStore,
      final ObjectMapper objectMapper,
      final SessionConfigProperties props) {
    this.inMemoryStore = inMemoryStore;
    this.fileStore = new FileSessionConfigStore(props, objectMapper);
    this.props = props;
  }

  /**
   * Get the configured SessionConfigStore based on the store-type property.
   *
   * @return the configured SessionConfigStore instance
   */
  public SessionConfigStore getStore() {
    final String storeType = props.getStoreType();
    log.info("Creating SessionConfigStore with type: {}", storeType);

    return switch (storeType) {
      case "in-memory" -> inMemoryStore;
      case "file" -> fileStore;
      default -> {
        log.warn("Unknown store type '{}', defaulting to in-memory", storeType);
        yield inMemoryStore;
      }
    };
  }
}
