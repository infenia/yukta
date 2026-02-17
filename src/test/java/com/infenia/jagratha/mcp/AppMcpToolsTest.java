package com.infenia.jagratha.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.service.AppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppMcpToolsTest {

  private AppMcpTools mcpTools;
  private AppService service;

  @BeforeEach
  void setUp() {
    service = Mockito.mock(AppService.class);
    mcpTools = new AppMcpTools(service);
  }

  @Test
  void testGetProjectStatus() {
    assertEquals(
        "Jagratha is managing the project and ready to run quality checks.",
        mcpTools.getProjectStatus());
  }

  @Test
  void testTriggerQualityChecksSuccess() {
    TaskResponse response = new TaskResponse("SUCCESS", "Done");
    when(service.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    StepVerifier.create(mcpTools.triggerQualityChecks())
        .expectNext("Status: SUCCESS\n\nOutput:\nDone")
        .verifyComplete();
  }

  @Test
  void testTriggerQualityChecksFailure() {
    TaskResponse response = new TaskResponse("FAILURE", "Error");
    when(service.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    StepVerifier.create(mcpTools.triggerQualityChecks())
        .expectNext("Status: FAILURE\n\nOutput:\nError")
        .verifyComplete();
  }
}
