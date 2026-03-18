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

import org.springframework.context.annotation.Configuration;

/**
 * JTE Native Image Configuration. Disables source directory scanning in precompiled mode to prevent
 * JTE from trying to load .jte files from the filesystem in native images.
 */
@Configuration
public class JteNativeImageConfig {

  /** Default constructor. */
  public JteNativeImageConfig() {
    super();
  }

  // Spring Boot's JTE autoconfiguration will create the TemplateEngine bean.
  // When gg.jte.usePrecompiledTemplates=true in application-prod.yaml,
  // Spring Boot's logic will use ClassPathTemplateLoader to load precompiled classes.
}
