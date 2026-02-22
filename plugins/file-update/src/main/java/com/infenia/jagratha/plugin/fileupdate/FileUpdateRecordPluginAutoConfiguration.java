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
package com.infenia.jagratha.plugin.fileupdate;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for FileUpdateRecord plugin. */
@AutoConfiguration
public class FileUpdateRecordPluginAutoConfiguration {

  /** Default constructor. */
  public FileUpdateRecordPluginAutoConfiguration() {
    super();
  }

  /**
   * Provide a FileUpdateRecordPlugin bean.
   *
   * @return the plugin bean
   */
  @Bean
  @ConditionalOnMissingBean
  public FileUpdateRecordPlugin fileUpdateRecordPlugin() {
    return new FileUpdateRecordPlugin();
  }
}
