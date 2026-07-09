// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the default WorkflowDefinitionStore when no other is present. */
@Configuration
@NoArgsConstructor
public class WorkflowDefinitionStoreConfiguration {

  /**
   * Creates the default InMemoryWorkflowDefinitionStore bean when no other WorkflowDefinitionStore
   * is available.
   *
   * @return the in-memory workflow definition store instance
   */
  @Bean
  @ConditionalOnMissingBean(WorkflowDefinitionStore.class)
  public WorkflowDefinitionStore inMemoryWorkflowDefinitionStore() {
    return new InMemoryWorkflowDefinitionStore();
  }
}
