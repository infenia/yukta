// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.config.DefaultsAutoConfiguration;
import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.plugin.store.KeyValueStore;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.store.SessionHistoryStore;
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
@AutoConfiguration(before = DefaultsAutoConfiguration.class)
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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

  /**
   * The node checkpoints, when {@code yukta.restart.store-type=postgres}.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @return the store
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.restart.store-type", havingValue = "postgres")
  public NodeCheckpointStore nodeCheckpointStore(
      final DatabaseClient client, final ObjectMapper mapper) {
    return new R2dbcNodeCheckpointStore(client, mapper);
  }

  /**
   * Leader election through a lease row, when {@code yukta.ha.mode=postgres}.
   *
   * @param client runs the statements
   * @param instanceId this server's id in the lease row (random if not set)
   * @param lease how long a renewal keeps the lease
   * @param meters where the {@code yukta.leader} gauge is registered
   * @return the election
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.ha.mode", havingValue = "postgres")
  public R2dbcLeaderElection leaderElection(
      final DatabaseClient client,
      @Value("${yukta.ha.instance-id:#{T(java.util.UUID).randomUUID().toString()}}")
          final String instanceId,
      @Value("${yukta.ha.lease:15s}") final java.time.Duration lease,
      final io.micrometer.core.instrument.MeterRegistry meters) {
    return new R2dbcLeaderElection(client, instanceId, lease, meters);
  }

  /**
   * The state of {@code KV_STORE} nodes, when {@code yukta.kv.store-type=postgres}.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @return the store
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.kv.store-type", havingValue = "postgres")
  public KeyValueStore keyValueStore(final DatabaseClient client, final ObjectMapper mapper) {
    return new R2dbcKeyValueStore(client, mapper);
  }

  /**
   * The chat history of {@code SESSION_MEMORY} nodes, when {@code
   * yukta.session-memory.store-type=postgres}.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @param ttl how long a session that is not written is kept; unset keeps all
   * @return the store
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.session-memory.store-type", havingValue = "postgres")
  public SessionHistoryStore sessionHistoryStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      @Value("${yukta.session-memory.ttl:#{null}}") final java.time.Duration ttl) {
    return new R2dbcSessionHistoryStore(client, mapper, ttl);
  }
}
