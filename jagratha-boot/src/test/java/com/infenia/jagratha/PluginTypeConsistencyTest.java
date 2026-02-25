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
package com.infenia.jagratha;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.jagratha.plugin.WorkflowPlugin;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PluginTypeConsistencyTest {

  @Autowired private List<WorkflowPlugin> plugins;

  private static final Pattern KEBAB_CASE_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  @Test
  void allPluginsShouldFollowKebabCaseNaming() {
    for (WorkflowPlugin plugin : plugins) {
      String type = plugin.getType();
      assertTrue(
          KEBAB_CASE_PATTERN.matcher(type).matches(),
          "Plugin "
              + plugin.getClass().getName()
              + " has invalid type name: '"
              + type
              + "'. It must be in kebab-case.");
    }
  }
}
