/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
