// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps sessions, workflow definitions and the execution log in PostgreSQL when {@code
 * yukta.session.store-type} is {@code postgres}. Connect with {@code spring.r2dbc.url} (run time);
 * the schema is created by Flyway from {@code spring.flyway.url} (a JDBC URL, since Flyway has no
 * R2DBC support), with {@code spring.flyway.user} and {@code spring.flyway.password}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "yukta.session.store-type", havingValue = "postgres")
public class PostgresPersistenceAutoConfiguration {

  /** Default constructor. */
  public PostgresPersistenceAutoConfiguration() {
    super();
  }

  /**
   * The workflow definitions.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @return the store
   */
  @Bean
  public WorkflowDefinitionStore workflowDefinitionStore(
      final DatabaseClient client, final ObjectMapper mapper) {
    return new R2dbcWorkflowDefinitionStore(client, mapper);
  }

  /**
   * The session configuration.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @param props execution timeout and directories
   * @param definitions where the workflows of a session are kept
   * @return the store
   */
  @Bean
  public SessionConfigStore sessionConfigStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final SessionConfigProperties props,
      final WorkflowDefinitionStore definitions) {
    return new R2dbcSessionConfigStore(client, mapper, props, definitions);
  }

  /**
   * The execution log.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   * @return the store
   */
  @Bean(destroyMethod = "close")
  public ExecutionLogStore executionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns,
      @Value("${yukta.execution-log.compact-after:10000}") final int compactAfter) {
    return new R2dbcExecutionLogStore(client, mapper, retainedRuns, compactAfter, 5_000);
  }
}
