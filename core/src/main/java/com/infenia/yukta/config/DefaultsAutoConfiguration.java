// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * The single-server defaults: an execution log and node checkpoints in memory, and a leader that is
 * always the leader. Each one steps aside when another bean of its type exists, so a module that
 * provides a store or an election replaces the default by declaring
 * {@code @AutoConfiguration(before = DefaultsAutoConfiguration.class)} (or by being scanned). The
 * property conditions are kept, so the file and PostgreSQL stores behave as before.
 */
@AutoConfiguration
@NoArgsConstructor
public class DefaultsAutoConfiguration {

  /**
   * The execution log in memory, trimmed like the file log.
   *
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean(ExecutionLogStore.class)
  @ConditionalOnExpression(
      "'${yukta.execution-log.dir:}'.isEmpty()"
          + " && !'postgres'.equals('${yukta.session.store-type:in-memory}')")
  public InMemoryExecutionLogStore inMemoryExecutionLogStore(
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns,
      @Value("${yukta.execution-log.compact-after:10000}") final int compactAfter) {
    return new InMemoryExecutionLogStore(retainedRuns, compactAfter);
  }

  /**
   * The node checkpoints in memory, with optional spill to files.
   *
   * @param messagesPerNode maximum number of messages kept per node and execution
   * @param bytesPerNode maximum payload bytes kept per node and execution
   * @param spillDir directory for output beyond the caps; empty to refuse such restarts
   * @param mapper writes spilled messages
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean(NodeCheckpointStore.class)
  @ConditionalOnExpression(
      "'${yukta.restart.dir:}'.isEmpty() && !'postgres'.equals('${yukta.restart.store-type:}')")
  public InMemoryNodeCheckpointStore inMemoryNodeCheckpointStore(
      @Value("${yukta.restart.messages-per-node:200}") final int messagesPerNode,
      @Value("${yukta.restart.bytes-per-node:4194304}") final long bytesPerNode,
      @Value("${yukta.restart.spill-dir:}") final String spillDir,
      final ObjectProvider<ObjectMapper> mapper) {
    return new InMemoryNodeCheckpointStore(messagesPerNode, bytesPerNode, spillDir, mapper);
  }

  /**
   * The election of a single server.
   *
   * @return the election; it is always the leader
   */
  @Bean
  @ConditionalOnMissingBean(LeaderElection.class)
  @ConditionalOnExpression("!'postgres'.equals('${yukta.ha.mode:}')")
  public SingleServerLeader singleServerLeader() {
    return new SingleServerLeader();
  }
}
