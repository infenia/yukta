package com.infenia.jagratha.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QwenCodePluginTest {

  private QwenCodePlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new QwenCodePlugin();
  }

  @Test
  void testGetName() {
    assertEquals("qwen-code", plugin.getName());
  }

  @Test
  void testExecuteFailsWhenQwenMissing() {
    // Assuming 'qwen' command is missing in the test environment
    String response = plugin.execute("Hello", Map.of());
    assertTrue(response.contains("Error executing Qwen"));
  }
}
