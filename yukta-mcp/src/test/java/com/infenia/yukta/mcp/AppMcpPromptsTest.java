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
