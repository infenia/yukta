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
package com.infenia.jagratha.plugin.processor.checkstyle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for Checkstyle processor plugin. */
@AutoConfiguration
public class CheckstyleProcessorAutoConfiguration {

  /** Public constructor for PMD. */
  public CheckstyleProcessorAutoConfiguration() {
    super();
  }

  /**
   * Provide a CheckstyleXmlProcessor bean if not already present and ObjectMapper is available.
   *
   * @param objectMapper the ObjectMapper
   * @return the CheckstyleXmlProcessor
   */
  @Bean
  @ConditionalOnBean(ObjectMapper.class)
  @ConditionalOnMissingBean
  public CheckstyleXmlProcessor checkstyleXmlProcessor(final ObjectMapper objectMapper) {
    return new CheckstyleXmlProcessor(objectMapper);
  }
}
