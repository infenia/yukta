// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
