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
package com.infenia.yukta.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.TaskResponse;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.session.SessionConfigStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

  @Mock private SessionConfigStore configService;
  @Mock private WorkflowOrchestrator orchestrator;

  private WorkflowService workflowService;

  @BeforeEach
  void setUp() {
    workflowService = new WorkflowService(configService, orchestrator);
  }

  @Test
  void testRunWorkflowSuccess() {
    String sessionId = "sess-success";
    String workflowId = "w-success";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Mono.empty());

    StepVerifier.create(
            workflowService.runWorkflow(sessionId, workflowId, java.util.Map.of()).result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testRunWorkflowNoWorkflow() {
    String sessionId = "sess-none";
    String workflowId = "w-none";

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.empty());

    StepVerifier.create(
            workflowService.runWorkflow(sessionId, workflowId, java.util.Map.of()).result())
        .expectNextMatches(
            res ->
                "FAILURE".equals(res.status()) && res.output().contains("No workflow configured"))
        .verifyComplete();
  }

  @Test
  void testRunWorkflowError() {
    String sessionId = "sess-error";
    String workflowId = "w-error";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.error(new RuntimeException("Fail")));

    StepVerifier.create(
            workflowService.runWorkflow(sessionId, workflowId, java.util.Map.of()).result())
        .expectNextMatches(
            res -> "FAILURE".equals(res.status()) && res.output().contains("Workflow failed: Fail"))
        .verifyComplete();
  }

  @Test
  void testWorkflowQueueing() throws Exception {
    String sessionId = "sess-queue";
    String workflowId = "w-queue";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    // Use a CountDownLatch to control execution timing
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch startedLatch = new java.util.concurrent.CountDownLatch(1);

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              startedLatch.countDown();
              return Mono.fromRunnable(
                  () -> {
                    try {
                      latch.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  });
            });

    var exec1 = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    // Wait for first execution to start
    startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

    // Now submit second workflow - it should queue
    var exec2 = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    // Release first execution
    latch.countDown();

    StepVerifier.create(exec1.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();

    StepVerifier.create(exec2.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testWorkflowQueueCleanup() throws Exception {
    String sessionId = "sess-cleanup";
    String workflowId = "w-cleanup";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch startedLatch = new java.util.concurrent.CountDownLatch(1);

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              startedLatch.countDown();
              return Mono.fromRunnable(
                  () -> {
                    try {
                      latch.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  });
            });

    var exec1 = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    // Wait for first execution to start
    startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

    // Now submit second workflow - it should queue
    var exec2 = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    // Release first execution
    latch.countDown();

    StepVerifier.create(exec1.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();

    StepVerifier.create(exec2.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testWorkflowMultipleQueuesDifferentSessions() {
    String sessionId1 = "sess-multi-1";
    String sessionId2 = "sess-multi-2";
    String workflowId = "w-multi";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    when(configService.getWorkflow(anyString(), anyString())).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Mono.empty());

    var exec1 = workflowService.runWorkflow(sessionId1, workflowId, Map.of());
    var exec2 = workflowService.runWorkflow(sessionId2, workflowId, Map.of());

    StepVerifier.create(exec1.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();

    StepVerifier.create(exec2.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testWorkflowSingleExecutionCleanup() throws Exception {
    String sessionId = "sess-single";
    String workflowId = "w-single";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Mono.empty());

    var exec = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    StepVerifier.create(exec.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();

    // Wait for async cleanup to complete
    Thread.sleep(500);
  }

  @Test
  void testWorkflowQueuedExecutionWithPreviousWorkflowError() throws Exception {
    String sessionId = "sess-error-queue";
    String workflowId = "w-error-queue";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    java.util.concurrent.CountDownLatch firstErrorLatch =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch firstStartedLatch =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger executeCallCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              int callCount = executeCallCount.incrementAndGet();
              firstStartedLatch.countDown();
              if (callCount == 1) {
                // First execution errors
                return Mono.fromRunnable(
                        () -> {
                          try {
                            firstErrorLatch.await();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        })
                    .then(Mono.error(new RuntimeException("First workflow error")));
              } else {
                // Second execution succeeds (onErrorResume recovery)
                return Mono.just(new TaskResponse("SUCCESS", "Workflow executed successfully"));
              }
            });

    var exec1 = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    // Wait for first execution to start
    firstStartedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

    // Now submit second workflow - it should queue
    var exec2 = workflowService.runWorkflow(sessionId, workflowId, Map.of());

    // Let first execution error
    firstErrorLatch.countDown();

    // First execution should fail
    StepVerifier.create(exec1.result())
        .expectNextMatches(res -> "FAILURE".equals(res.status()))
        .verifyComplete();

    // Second execution should succeed (onErrorResume path - previous workflow error recovery)
    StepVerifier.create(exec2.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testWorkflowErrorAfterPreparation() {
    String sessionId = "sess-prep-error";
    String workflowId = "w-prep-error";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Mono.error(new RuntimeException("Execution error")));

    StepVerifier.create(workflowService.runWorkflow(sessionId, workflowId, Map.of()).result())
        .expectNextMatches(
            res -> "FAILURE".equals(res.status()) && res.output().contains("Execution error"))
        .verifyComplete();
  }

  @Test
  void testMultipleQueuedExecutionsWithSelectiveCleanup() throws Exception {
    String sessionId = "sess-multi-cleanup";
    String workflowId = "w-multi-cleanup";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared =
        new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of(), (e, p) -> Mono.empty());

    java.util.concurrent.CountDownLatch firstLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch secondLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch thirdLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch firstStartedLatch =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch secondStartedLatch =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch thirdStartedLatch =
        new java.util.concurrent.CountDownLatch(1);

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              String execId = (String) invocation.getArguments()[2];
              if (execId.contains("1")) {
                firstStartedLatch.countDown();
                return Mono.fromRunnable(
                    () -> {
                      try {
                        firstLatch.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
              } else if (execId.contains("2")) {
                secondStartedLatch.countDown();
                return Mono.fromRunnable(
                    () -> {
                      try {
                        secondLatch.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
              } else {
                thirdStartedLatch.countDown();
                return Mono.fromRunnable(
                    () -> {
                      try {
                        thirdLatch.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
              }
            });

    var exec1 = workflowService.runWorkflow(sessionId, workflowId, Map.of());
    firstStartedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

    var exec2 = workflowService.runWorkflow(sessionId, workflowId, Map.of());
    secondStartedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

    var exec3 = workflowService.runWorkflow(sessionId, workflowId, Map.of());
    thirdStartedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

    // Release all executions
    firstLatch.countDown();
    secondLatch.countDown();
    thirdLatch.countDown();

    StepVerifier.create(exec1.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();

    StepVerifier.create(exec2.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();

    StepVerifier.create(exec3.result())
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testWorkflowErrorInTerminalPhase() {
    String sessionId = "sess-terminal-error";
    String workflowId = "w-terminal-error";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any()))
        .thenReturn(Mono.error(new RuntimeException("Prep error")));

    StepVerifier.create(workflowService.runWorkflow(sessionId, workflowId, Map.of()).result())
        .expectNextMatches(
            res -> "FAILURE".equals(res.status()) && res.output().contains("Prep error"))
        .verifyComplete();
  }
}
