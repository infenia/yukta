// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Auto-configuration for {@link AgentSessionPlugin}. */
@AutoConfiguration
public class AgentSessionAutoConfiguration {

  /** Default constructor. */
  public AgentSessionAutoConfiguration() {
    super();
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
   * The terminal tier: off unless {@code yukta.agents.pty.enabled} says so.
   *
   * @param enabled whether a terminal agent may run
   * @param host the executable that provides {@code pty-host}
   * @param cols the width of a new terminal
   * @param rows the height of a new terminal
   * @return the settings
   */
  @Bean
  @ConditionalOnMissingBean
  public PtySettings ptySettings(
      @Value("${yukta.agents.pty.enabled:false}") final boolean enabled,
      @Value("${yukta.agents.pty.host:yukta-cli}") final String host,
      @Value("${yukta.agents.pty.cols:120}") final int cols,
      @Value("${yukta.agents.pty.rows:40}") final int rows) {
    return new PtySettings(enabled, host, cols, rows);
  }

  /**
   * Create AgentSessionPlugin bean if not already defined.
   *
   * @param gateway the process executor gateway
   * @param resolver the variable resolver
   * @param harnesses the agent harness presets
   * @param sessions remembers sessions between turns
   * @param inputs the input request system, for approvals
   * @param conversations records the conversations; absent when {@code yukta.agents.enabled} is
   *     false
   * @param json the JSON mapper
   * @param pty the terminal tier
   * @return the agent session plugin
   */
  @Bean
  @ConditionalOnMissingBean
  public AgentSessionPlugin agentSessionPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final AgentHarnesses harnesses,
      final AgentSessionStore sessions,
      final ObjectProvider<InputGateway> inputs,
      final ObjectProvider<AgentConversationGateway> conversations,
      final ObjectMapper json,
      final PtySettings pty) {
    return new AgentSessionPlugin(
        gateway, resolver, harnesses, sessions, inputs, conversations, json, pty);
  }
}
