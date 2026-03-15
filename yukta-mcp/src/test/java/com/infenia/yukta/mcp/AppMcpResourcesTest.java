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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class AppMcpResourcesTest {

  private AppMcpResources mcpResources;
  private DefaultLogProvider logProvider;

  @BeforeEach
  void setUp() {
    logProvider = mock(DefaultLogProvider.class);
    mcpResources = new AppMcpResources(logProvider);
  }

  @Test
  void testGetYuktaOverview() {
    StepVerifier.create(mcpResources.getYuktaOverview())
        .assertNext(
            overview -> {
              assertTrue(overview.contains("Yukta"));
              assertTrue(overview.contains("DAG"));
            })
        .verifyComplete();
  }

  @Test
  void testGetYuktaArchitectureDocs() {
    StepVerifier.create(mcpResources.getYuktaArchitectureDocs())
        .assertNext(
            docs -> {
              assertTrue(docs.contains("Architecture"));
              assertTrue(docs.contains("Reactive"));
            })
        .verifyComplete();
  }

  @Test
  void testGetSessionLogs() {
    when(logProvider.streamSessionLogs(eq("s1"), any(), any(), any()))
        .thenReturn(Flux.just("line1", "line2"));

    StepVerifier.create(mcpResources.getSessionLogs("s1"))
        .expectNext("line1\nline2")
        .verifyComplete();
  }
}
