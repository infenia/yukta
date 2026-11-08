// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.engine.EngineSupervisorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Offers the {@code http} engine as a supervisor type. */
@Configuration(proxyBeanMethods = false)
public class AgentEngineConfiguration {

  /**
   * The {@code http} supervisor type.
   *
   * @param hub where the agent API meets the engines
   * @return the factory
   */
  @Bean
  public SupervisorFactory httpSupervisorFactory(final AgentHub hub) {
    return new EngineSupervisorFactory(new HttpEngineFactory(hub));
  }
}
