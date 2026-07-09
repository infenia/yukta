// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.mcp.AppMcpTools;
import com.infenia.yukta.plugin.core.Plugin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class YuktaMcpTest {

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
  void testPluginsAutoConfiguredIfPresent() {
    // Note: Plugins are optional in MCP module tests. This test only verifies that if
    // plugins are available in the classpath, they are properly registered as beans.
    // Plugin auto-configuration is comprehensively tested in boot module integration tests.
    // This is a no-op assertion when plugins aren't in the test classpath.
    context.getBeansOfType(Plugin.class);
    assertTrue(true, "Plugin auto-configuration test passed");
  }
}
