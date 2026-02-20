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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.jagratha.mcp.AppMcpTools;
import com.infenia.jagratha.plugin.WorkflowPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class JagrathaMcpTest {

  @Autowired private ApplicationContext context;

  @Test
  void testMcpToolsBeanExists() {
    AppMcpTools tools = context.getBean(AppMcpTools.class);
    assertNotNull(tools);
  }

  @Test
  void testMcpServerAutoConfigured() {
    // Check if some MCP server related beans exist
    assertTrue(context.containsBean("mcpServer") || context.containsBean("mcpSyncServer"));
  }

  @Test
  void testPluginsAutoConfigured() {
    Map<String, WorkflowPlugin> plugins = context.getBeansOfType(WorkflowPlugin.class);
    assertFalse(plugins.isEmpty(), "No WorkflowPlugin beans found");
    assertTrue(plugins.containsKey("gradlePlugin"), "gradlePlugin bean missing");
  }
}
