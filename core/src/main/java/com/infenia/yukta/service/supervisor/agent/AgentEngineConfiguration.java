// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.engine.EngineSupervisorFactory;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/** Offers the {@code http} engine as a supervisor type. */
@Configuration(proxyBeanMethods = false)
public class AgentEngineConfiguration {

  /**
   * The {@code http} supervisor type.
   *
   * @param hub where the agent API meets the engines
   * @param policy the deployment's host lists
   * @param hosts {@code yukta.http.allowed-hosts}, which may name hosts inside the network
   * @param client builds the client that posts the signals
   * @param json writes the posted signals as the agent API does
   * @return the factory
   */
  @Bean
  public SupervisorFactory httpSupervisorFactory(
      final AgentHub hub,
      final EgressPolicy policy,
      @Value("${yukta.http.allowed-hosts:}") final List<String> hosts,
      final WebClient.Builder client,
      final JsonMapper json) {
    final Outbound outbound =
        new Outbound(
            new Egress(policy, HostAllowList.from(hosts)), client.build(), json, System::getenv);
    return new EngineSupervisorFactory(new HttpEngineFactory(hub, outbound));
  }
}
