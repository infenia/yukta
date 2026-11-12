// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.util.VariableResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Auto-configuration for ProcessExecutorPlugin. */
@AutoConfiguration
public class ProcessExecutorAutoConfiguration {

  /** Default constructor. */
  public ProcessExecutorAutoConfiguration() {
    super();
  }

  /**
   * Create ProcessExecutorPlugin bean if not already defined.
   *
   * @param gateway the process executor gateway
   * @param resolver the variable resolver
   * @return the process executor plugin
   */
  @Bean
  @ConditionalOnMissingBean
  public ProcessExecutorPlugin processExecutorPlugin(
      final ProcessExecutorGateway gateway, final VariableResolver resolver) {
    return new ProcessExecutorPlugin(gateway, resolver);
  }

  /**
   * The agent harness presets: the shipped ones and any in {@code yukta.ai.presets-dir}.
   *
   * @param json the JSON mapper
   * @param dir the operator's preset directory, or blank
   * @return the presets
   * @throws java.io.IOException when a preset cannot be read
   */
  @Bean
  @ConditionalOnMissingBean
  public AgentHarnesses agentHarnesses(
      final ObjectMapper json, @Value("${yukta.ai.presets-dir:}") final String dir)
      throws java.io.IOException {
    return new AgentHarnesses(json, dir);
  }

  /**
   * Where agent sessions are remembered: in memory.
   *
   * @return the store
   */
  @Bean
  @ConditionalOnMissingBean
  public AgentSessionStore agentSessionStore() {
    return new InMemoryAgentSessionStore();
  }

  /**
   * Create AgentSessionPlugin bean if not already defined.
   *
   * @param gateway the process executor gateway
   * @param resolver the variable resolver
   * @param harnesses the agent harness presets
   * @param sessions remembers sessions between turns
   * @param json the JSON mapper
   * @return the agent session plugin
   */
  @Bean
  @ConditionalOnMissingBean
  public AgentSessionPlugin agentSessionPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final AgentHarnesses harnesses,
      final AgentSessionStore sessions,
      final ObjectMapper json) {
    return new AgentSessionPlugin(gateway, resolver, harnesses, sessions, json);
  }
}
