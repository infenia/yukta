// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.edition.EditionInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.service.supervisor.InMemorySupervisorStore;
import com.infenia.yukta.service.supervisor.SupervisorStore;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * The single-server defaults: an execution log and node checkpoints in memory, and a leader that is
 * always the leader. Each one steps aside when another bean of its type exists, so a module that
 * provides a store or an election replaces the default by declaring
 * {@code @AutoConfiguration(before = DefaultsAutoConfiguration.class)} (or by being scanned). The
 * property conditions are kept, so the file and PostgreSQL stores behave as before.
 */
@AutoConfiguration
@EnableConfigurationProperties(YuktaProperties.class)
@NoArgsConstructor
public class DefaultsAutoConfiguration {

  /**
   * The execution log in memory, trimmed like the file log.
   *
   * @param properties the settings
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean(ExecutionLogStore.class)
  @ConditionalOnExpression(
      "'${yukta.execution-log.dir:}'.isEmpty()"
          + " && !'postgres'.equals('${yukta.session.store-type:in-memory}')")
  public InMemoryExecutionLogStore inMemoryExecutionLogStore(final YuktaProperties properties) {
    return new InMemoryExecutionLogStore(
        properties.executionLog().retainedRuns(), properties.executionLog().compactAfter());
  }

  /**
   * The node checkpoints in memory, with optional spill to files.
   *
   * @param properties the settings
   * @param mapper writes spilled messages
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean(NodeCheckpointStore.class)
  @ConditionalOnExpression(
      "'${yukta.restart.dir:}'.isEmpty() && !'postgres'.equals('${yukta.restart.store-type:}')")
  public InMemoryNodeCheckpointStore inMemoryNodeCheckpointStore(
      final YuktaProperties properties, final ObjectProvider<ObjectMapper> mapper) {
    final YuktaProperties.Restart restart = properties.restart();
    return new InMemoryNodeCheckpointStore(
        restart.messagesPerNode(), restart.bytesPerNode(), restart.spillDir(), mapper);
  }

  /**
   * Supervisor profiles and bindings in memory, unless another module keeps them.
   *
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean(SupervisorStore.class)
  public InMemorySupervisorStore inMemorySupervisorStore() {
    return new InMemorySupervisorStore();
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

  /**
   * Who may call the REST API and MCP: the API keys of the configuration.
   *
   * @param environment holds the API keys
   * @return the authenticator
   */
  @Bean
  @ConditionalOnMissingBean(ApiAuthenticator.class)
  public ApiKeyAuthenticator apiKeyAuthenticator(final Environment environment) {
    return ApiKeyAuthenticator.read(environment);
  }

  /**
   * The edition of a build that has no edition module.
   *
   * @return the Community info
   */
  @Bean
  @ConditionalOnMissingBean(EditionInfo.class)
  public EditionInfo communityEdition() {
    return EditionInfo.community();
  }
}
