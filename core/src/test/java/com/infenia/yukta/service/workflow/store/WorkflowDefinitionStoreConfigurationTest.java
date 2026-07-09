// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

/** Unit tests for {@link WorkflowDefinitionStoreConfiguration}. */
@NoArgsConstructor
class WorkflowDefinitionStoreConfigurationTest {

  @Test
  void inMemoryWorkflowDefinitionStore_createsValidInstance() {
    // Given & When
    final WorkflowDefinitionStoreConfiguration config = new WorkflowDefinitionStoreConfiguration();
    final WorkflowDefinitionStore store = config.inMemoryWorkflowDefinitionStore();

    // Then
    assertThat(store).isNotNull();
    assertThat(store).isInstanceOf(InMemoryWorkflowDefinitionStore.class);
  }

  @Test
  void configuration_classHasValidSpringAnnotations() {
    // Given
    final WorkflowDefinitionStoreConfiguration config = new WorkflowDefinitionStoreConfiguration();

    // When & Then
    assertThat(config).isNotNull();
    assertThat(config.getClass().isAnnotationPresent(Configuration.class)).isTrue();
  }
}
