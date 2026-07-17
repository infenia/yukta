// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
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
  void testGetExecutionLogs() {
    when(logProvider.getExecutionLogs("s1", "e1", null, null))
        .thenReturn(Mono.just(new ExecutionLogs("e1", 2, 2, List.of("line1", "line2"))));

    StepVerifier.create(mcpResources.getExecutionLogs("s1", "e1"))
        .expectNext("line1\nline2")
        .verifyComplete();
  }
}
