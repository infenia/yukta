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
package com.infenia.yukta.ui.config;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * JTE Native Image Configuration. Provides explicit TemplateEngine bean configured for precompiled
 * templates in native image mode. This prevents JTE from attempting dynamic template compilation,
 * which fails in native images due to missing filesystem access.
 */
@Slf4j
@Configuration
public class JteNativeImageConfig {

  /** Default constructor. */
  public JteNativeImageConfig() {
    super();
  }

  /**
   * Provide a TemplateEngine bean that uses precompiled templates. This bean has the same name as
   * the autoconfigured bean, ensuring it takes precedence and replaces the default autoconfigured
   * TemplateEngine which would try dynamic compilation (failing in native images).
   */
  @Primary
  @Bean(name = "templateEngine")
  public TemplateEngine templateEngine() {
    return TemplateEngine.createPrecompiled(ContentType.Html);
  }
}
