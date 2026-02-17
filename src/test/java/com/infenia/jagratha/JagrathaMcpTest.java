package com.infenia.jagratha;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.jagratha.mcp.AppMcpTools;
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
}
