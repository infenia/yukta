// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

/** Unit tests for {@link SessionConfigStoreConfiguration}. */
@NoArgsConstructor
class SessionConfigStoreConfigurationTest {

  @Test
  void inMemorySessionConfigStore_createsValidInstance() {
    // Given
    final SessionConfigProperties props = new SessionConfigProperties();
    final WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);
    final SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();

    // When
    final SessionConfigStore sessionStore = config.inMemorySessionConfigStore(props, store);

    // Then
    assertThat(sessionStore).isNotNull();
    assertThat(sessionStore).isInstanceOf(InMemorySessionConfigStore.class);
  }

  @Test
  void configuration_classHasValidSpringAnnotations() {
    // Given
    final SessionConfigStoreConfiguration config = new SessionConfigStoreConfiguration();

    // When & Then
    assertThat(config).isNotNull();
    assertThat(config.getClass().isAnnotationPresent(Configuration.class)).isTrue();
  }
}
