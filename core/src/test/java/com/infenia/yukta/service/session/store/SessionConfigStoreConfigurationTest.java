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
package com.infenia.yukta.service.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

@SpringBootTest
class SessionConfigStoreConfigurationTest {

  @Autowired private SessionConfigStore sessionConfigStore;

  @Autowired private SessionConfigProperties sessionConfigProperties;

  @Autowired private WorkflowDefinitionStore workflowDefinitionStore;

  @Test
  void inMemorySessionConfigStore_isCreated_whenNoOtherBeanPresent() {
    // Given & When
    // Spring context is initialized with default configuration

    // Then
    assertThat(sessionConfigStore).isNotNull();
    assertThat(sessionConfigStore).isInstanceOf(InMemorySessionConfigStore.class);
  }

  @Test
  void inMemorySessionConfigStore_createsValidInstance() {
    // Given
    SessionConfigProperties props = new SessionConfigProperties();
    WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();

    // When
    SessionConfigStore sessionStore = config.inMemorySessionConfigStore(props, store);

    // Then
    assertThat(sessionStore).isNotNull();
    assertThat(sessionStore).isInstanceOf(InMemorySessionConfigStore.class);
  }

  @Test
  void inMemorySessionConfigStore_isProperlyInjectedWithDependencies() {
    // Given & When
    // Spring context is initialized with dependencies injected

    // Then
    assertThat(sessionConfigStore).isNotNull();
    assertThat(sessionConfigProperties).isNotNull();
    assertThat(workflowDefinitionStore).isNotNull();
  }

  @Test
  void configuration_classHasValidSpringAnnotations() {
    // Given
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();

    // When & Then
    assertThat(config).isNotNull();
    assertThat(config.getClass().isAnnotationPresent(Configuration.class)).isTrue();
  }

  @Test
  void inMemorySessionConfigStore_acceptsNullProperties() {
    // Given
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();
    WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);

    // When
    SessionConfigStore sessionStore = config.inMemorySessionConfigStore(null, store);

    // Then
    assertThat(sessionStore).isNotNull();
  }

  @Test
  void inMemorySessionConfigStore_acceptsNullWorkflowDefinitionStore() {
    // Given
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();
    SessionConfigProperties props = new SessionConfigProperties();

    // When
    SessionConfigStore sessionStore = config.inMemorySessionConfigStore(props, null);

    // Then
    assertThat(sessionStore).isNotNull();
  }

  @Test
  void inMemorySessionConfigStore_multipleCallsReturnDifferentInstances() {
    // Given
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();
    SessionConfigProperties props = new SessionConfigProperties();
    WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);

    // When
    SessionConfigStore sessionStore1 = config.inMemorySessionConfigStore(props, store);
    SessionConfigStore sessionStore2 = config.inMemorySessionConfigStore(props, store);

    // Then
    assertThat(sessionStore1).isNotNull();
    assertThat(sessionStore2).isNotNull();
    assertThat(sessionStore1).isNotSameAs(sessionStore2);
  }

  @Test
  void beanMethodCanBeCalledDirectly() {
    // Given
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();
    SessionConfigProperties props = new SessionConfigProperties();
    WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);

    // When
    SessionConfigStore sessionStore = config.inMemorySessionConfigStore(props, store);

    // Then
    assertThat(sessionStore).isNotNull();
    assertThat(sessionStore).isInstanceOf(SessionConfigStore.class);
  }

  @Test
  void configuration_withBothNullDependencies_stillCreatesInstance() {
    // Given
    SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();

    // When
    SessionConfigStore sessionStore = config.inMemorySessionConfigStore(null, null);

    // Then
    assertThat(sessionStore).isNotNull();
    assertThat(sessionStore).isInstanceOf(SessionConfigStore.class);
  }
}
