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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.yukta.config.SessionConfigProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SessionConfigStoreFactoryTest {

  @ParameterizedTest
  @ValueSource(strings = {"in-memory", "file"})
  void testFactoryReturnsCorrectStoreType(final String storeType) {
    SessionConfigProperties props = new SessionConfigProperties();
    props.setStoreType(storeType);
    InMemorySessionConfigStore inMemoryStore = new InMemorySessionConfigStore(props);
    ObjectMapper objectMapper = new ObjectMapper();
    SessionConfigStoreFactory factory =
        new SessionConfigStoreFactory(inMemoryStore, objectMapper, props);

    SessionConfigStore store = factory.getStore();

    assertNotNull(store);
    if ("in-memory".equals(storeType)) {
      assertInstanceOf(InMemorySessionConfigStore.class, store);
    } else if ("file".equals(storeType)) {
      assertInstanceOf(FileSessionConfigStore.class, store);
    }
  }

  @org.junit.jupiter.api.Test
  void testFactoryDefaultsToInMemoryForUnknownType() {
    SessionConfigProperties props = new SessionConfigProperties();
    props.setStoreType("unknown-type");
    InMemorySessionConfigStore inMemoryStore = new InMemorySessionConfigStore(props);
    ObjectMapper objectMapper = new ObjectMapper();
    SessionConfigStoreFactory factory =
        new SessionConfigStoreFactory(inMemoryStore, objectMapper, props);

    SessionConfigStore store = factory.getStore();

    assertNotNull(store);
    assertInstanceOf(InMemorySessionConfigStore.class, store);
  }
}
