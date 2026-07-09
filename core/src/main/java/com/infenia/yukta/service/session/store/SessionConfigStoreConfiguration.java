// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session.store;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the default SessionConfigStore when no other is present. */
@Configuration
@NoArgsConstructor
public class SessionConfigStoreConfiguration {

  /**
   * Creates the default InMemorySessionConfigStore bean when no other SessionConfigStore is
   * available.
   *
   * @param properties the session config properties
   * @param workflowDefinitionStore the workflow definition store
   * @return the in-memory session config store instance
   */
  @Bean
  @ConditionalOnMissingBean(SessionConfigStore.class)
  public SessionConfigStore inMemorySessionConfigStore(
      final SessionConfigProperties properties,
      final WorkflowDefinitionStore workflowDefinitionStore) {
    return new InMemorySessionConfigStore(properties, workflowDefinitionStore);
  }
}
