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
package com.infenia.jagratha.plugin.ai.qwen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.plugin.ValidationResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QwenCodePluginTest {

  private QwenCodePlugin plugin;
  private QwenCodePlugin.ProcessExecutor mockExecutor;

  @BeforeEach
  void setUp() {
    mockExecutor = mock(QwenCodePlugin.ProcessExecutor.class);
    plugin = new QwenCodePlugin(mockExecutor);
  }

  @Test
  void testGetName() {
    assertEquals("qwen-code", plugin.getName());
  }

  @Test
  void testExecuteSuccess() throws IOException, InterruptedException {
    Process mockProcess = mock(Process.class);
    String output = "AI feedback";
    when(mockProcess.getInputStream())
        .thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
    when(mockProcess.waitFor()).thenReturn(0);
    when(mockExecutor.execute(anyList())).thenReturn(mockProcess);

    String response = plugin.execute("Hello", Map.of());
    assertEquals(output, response);
  }

  @Test
  void testExecuteFailure() throws IOException, InterruptedException {
    Process mockProcess = mock(Process.class);
    String output = "Execution error";
    when(mockProcess.getInputStream())
        .thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
    when(mockProcess.waitFor()).thenReturn(1);
    when(mockExecutor.execute(anyList())).thenReturn(mockProcess);

    String response = plugin.execute("Hello", Map.of());
    assertTrue(response.contains("Error executing Qwen (exit code 1)"));
    assertTrue(response.contains(output));
  }

  @Test
  void testExecuteIOException() throws IOException {
    when(mockExecutor.execute(anyList())).thenThrow(new IOException("Cmd not found"));

    String response = plugin.execute("Hello", Map.of());
    assertTrue(response.contains("Error executing Qwen: Cmd not found"));
  }

  @Test
  void testValidateConfigSuccess() {
    ValidationResult result = plugin.validateConfig(Map.of());
    assertTrue(result.valid());
  }

  @Test
  void testValidateConfigNull() {
    ValidationResult result = plugin.validateConfig(null);
    assertFalse(result.valid());
    assertEquals("Configuration is required", result.message());
  }
}
