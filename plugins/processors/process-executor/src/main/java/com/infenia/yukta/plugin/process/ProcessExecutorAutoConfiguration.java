// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.util.VariableResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

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
}
