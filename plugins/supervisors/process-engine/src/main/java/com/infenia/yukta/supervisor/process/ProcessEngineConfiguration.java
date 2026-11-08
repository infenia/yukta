// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.process;

import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.engine.EngineSupervisorFactory;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Offers the {@code process} engine as a supervisor type. */
@Configuration(proxyBeanMethods = false)
public class ProcessEngineConfiguration {

  /**
   * The {@code process} supervisor type.
   *
   * @param allowed the executables that may run
   * @param allowAny whether any executable may run when that list is empty
   * @return the factory
   */
  @Bean
  public SupervisorFactory processSupervisorFactory(
      @Value("${yukta.process.allowed-commands:}") final List<String> allowed,
      @Value("${yukta.process.allow-any:false}") final boolean allowAny) {
    return new EngineSupervisorFactory(
        new ProcessEngineFactory(
            allowed == null
                ? Set.of()
                : Set.copyOf(allowed.stream().filter(s -> !s.isBlank()).toList()),
            allowAny));
  }
}
