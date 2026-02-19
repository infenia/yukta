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
package com.infenia.jagratha.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import gg.jte.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = JteConfiguration.class)
@TestPropertySource(properties = "jte.usePrecompiledTemplates=false")
class JteConfigurationTest {

  @Autowired private TemplateEngine templateEngine;

  @Test
  void testTemplateEngineBean() {
    assertNotNull(templateEngine);
  }
}
