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
