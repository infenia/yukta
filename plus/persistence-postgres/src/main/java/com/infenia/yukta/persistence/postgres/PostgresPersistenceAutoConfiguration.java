// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.service.supervisor.SupervisorStore;
import com.infenia.yukta.service.supervisor.agent.AgentCredentialStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.startup.DefaultsAutoConfiguration;
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
   * @param definitions where the workflows of a session are kept
   * @return the store
   */
  @Bean
  public SessionConfigStore sessionConfigStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final WorkflowDefinitionStore definitions) {
    return new R2dbcSessionConfigStore(client, mapper, definitions);
  }

  /**
   * The execution log.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   * @param meters where lost events are counted
   * @param leaderElection the election whose fencing number guards the writes, if one runs
   * @return the store
   */
  @Bean(destroyMethod = "close")
  public ExecutionLogStore executionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns,
      @Value("${yukta.execution-log.compact-after:10000}") final int compactAfter,
      final org.springframework.beans.factory.ObjectProvider<
              io.micrometer.core.instrument.MeterRegistry>
          meters,
      final org.springframework.beans.factory.ObjectProvider<R2dbcLeaderElection> leaderElection) {
    return new R2dbcExecutionLogStore(
        client,
        mapper,
        retainedRuns,
        compactAfter,
        5_000,
        meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new),
        leaderElection.stream().findFirst().map(Fencing::tokenOf).orElse(null));
  }

  /**
   * The node checkpoints, when {@code yukta.restart.store-type=postgres}.
   *
   * @param client runs the statements
   * @param mapper reads and writes JSON
   * @param meters where refused writes are counted
   * @param leaderElection the election whose fencing number guards the writes, if one runs
   * @return the store
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.restart.store-type", havingValue = "postgres")
  public NodeCheckpointStore nodeCheckpointStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final org.springframework.beans.factory.ObjectProvider<
              io.micrometer.core.instrument.MeterRegistry>
          meters,
      final org.springframework.beans.factory.ObjectProvider<R2dbcLeaderElection> leaderElection) {
    return new R2dbcNodeCheckpointStore(
        client,
        mapper,
        meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new),
        leaderElection.stream().findFirst().map(Fencing::tokenOf).orElse(null));
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
   * Input requests, their answers and queued deliveries, when {@code
   * yukta.input.store-type=postgres}; otherwise they stay in memory and are lost on a restart.
   *
   * @param client runs the statements
   * @param mapper reads and writes the JSON columns
   * @return the store
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.input.store-type", havingValue = "postgres")
  public InputRequestStore inputRequestStore(
      final DatabaseClient client, final ObjectMapper mapper) {
    return new R2dbcInputRequestStore(client, mapper);
  }

  /**
   * Supervisor profiles and bindings, so those attached at run time survive a restart.
   *
   * @param client runs the statements
   * @param mapper reads and writes the settings as JSON
   * @return the store
   */
  @Bean
  public SupervisorStore supervisorStore(final DatabaseClient client, final ObjectMapper mapper) {
    return new R2dbcSupervisorStore(client, mapper);
  }

  /**
   * The hashes of the agent credentials, so they survive a restart.
   *
   * @param client runs the statements
   * @return the store
   */
  @Bean
  public AgentCredentialStore agentCredentialStore(final DatabaseClient client) {
    return new R2dbcAgentCredentialStore(client);
  }
}
