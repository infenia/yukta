// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AppMcpPromptsTest {

  private final AppMcpPrompts mcpPrompts = new AppMcpPrompts();

  @Test
  void testDebugWorkflow() {
    StepVerifier.create(mcpPrompts.debugWorkflow("s1", "e1"))
        .assertNext(
            prompt -> {
              assertTrue(prompt.contains("s1"));
              assertTrue(prompt.contains("e1"));
              assertTrue(prompt.contains("debugging"));
            })
        .verifyComplete();
  }

  @Test
  void testCreateSessionConfig() {
    StepVerifier.create(mcpPrompts.createSessionConfig())
        .assertNext(
            prompt -> {
              assertTrue(prompt.contains("JSON"));
              assertTrue(prompt.contains("Yukta session"));
            })
        .verifyComplete();
  }
}
