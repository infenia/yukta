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

import com.infenia.yukta.model.session.TaskResponse;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.session.SessionConfigStore;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Service for orchestrating workflow execution. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings("PMD.LongVariable")
public class WorkflowService {

  // Logging key constants
  private static final String LOG_KEY_SESSION_ID = "sessionId";
  private static final String LOG_KEY_WORKFLOW_ID = "workflowId";
  private static final String LOG_KEY_EXECUTION_ID = "executionId";
  private static final String LOG_KEY_QUEUE_KEY = "queueKey";
  private static final String LOG_KEY_ERROR_MSG = "errorMessage";

  private final SessionConfigStore configService;
  private final WorkflowOrchestrator orchestrator;

  private final Map<String, Mono<Void>> workflowQueues = new ConcurrentHashMap<>();

  /**
   * Run a specific workflow for a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payload the initial trigger payload
   * @return a WorkflowExecution containing the execution ID and the result Mono
   */
  public WorkflowExecution runWorkflow(
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      @NotEmpty final Map<String, Object> payload) {
    final String executionId = UUID.randomUUID().toString();
    final String queueKey = sessionId + ":" + workflowId;
    final Sinks.One<TaskResponse> sink = Sinks.one();

    log.atInfo()
        .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
        .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
        .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
        .log("Submitting workflow execution request");

    workflowQueues
        .compute(
            queueKey,
            (key, previousTail) -> {
              log.atDebug()
                  .addKeyValue(LOG_KEY_QUEUE_KEY, queueKey)
                  .addKeyValue("queuedExecutions", previousTail != null ? 1 : 0)
                  .log("Processing workflow queue");

              final Mono<Void> current =
                  Mono.defer(
                          () ->
                              configService
                                  .getWorkflow(sessionId, workflowId)
                                  .doOnSuccess(
                                      def ->
                                          log.atTrace()
                                              .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                                              .log("Workflow definition loaded"))
                                  .flatMap(
                                      def ->
                                          orchestrator
                                              .prepareWorkflow(def)
                                              .doOnSuccess(
                                                  prepared ->
                                                      log.atDebug()
                                                          .addKeyValue(
                                                              LOG_KEY_EXECUTION_ID, executionId)
                                                          .log("Workflow prepared successfully"))
                                              .flatMap(
                                                  prepared ->
                                                      orchestrator.execute(
                                                          sessionId,
                                                          workflowId,
                                                          executionId,
                                                          prepared,
                                                          payload))
                                              .then(
                                                  Mono.just(
                                                      new TaskResponse(
                                                          "SUCCESS",
                                                          "Workflow executed successfully"))))
                                  .switchIfEmpty(
                                      Mono.fromRunnable(
                                              () ->
                                                  log.atWarn()
                                                      .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                                                      .log("No workflow configuration found"))
                                          .then(
                                              Mono.just(
                                                  new TaskResponse(
                                                      "FAILURE",
                                                      "No workflow configured with ID: "
                                                          + workflowId))))
                                  .onErrorResume(
                                      e -> {
                                        log.atError()
                                            .setCause(e)
                                            .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                                            .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                                            .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                                            .addKeyValue(LOG_KEY_ERROR_MSG, e.getMessage())
                                            .log("Workflow execution failed");
                                        return Mono.just(
                                            new TaskResponse(
                                                "FAILURE", "Workflow failed: " + e.getMessage()));
                                      })
                                  .flatMap(
                                      response -> {
                                        log.atTrace()
                                            .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                                            .addKeyValue("responseStatus", response.status())
                                            .log("Workflow response generated");
                                        sink.tryEmitValue(response);
                                        return Mono.empty();
                                      })
                                  .then())
                      .subscribeOn(Schedulers.boundedElastic());

              final Mono<Void> nextTail;
              if (previousTail == null) {
                log.atTrace()
                    .addKeyValue(LOG_KEY_QUEUE_KEY, queueKey)
                    .log("First execution in queue, starting immediately");
                nextTail = current;
              } else {
                log.atDebug()
                    .addKeyValue(LOG_KEY_QUEUE_KEY, queueKey)
                    .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                    .log("Queueing execution behind previous workflow");
                nextTail =
                    previousTail
                        .onErrorResume(
                            e -> {
                              log.atWarn()
                                  .setCause(e)
                                  .addKeyValue(LOG_KEY_QUEUE_KEY, queueKey)
                                  .log("Previous workflow failed, proceeding with current");
                              return Mono.empty();
                            })
                        .then(current);
              }
              @SuppressWarnings("unchecked")
              final Mono<Void>[] tailRef = new Mono[1];
              tailRef[0] =
                  nextTail
                      .doOnSuccess(
                          v ->
                              log.atDebug()
                                  .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                                  .log("Workflow completed successfully"))
                      .doOnError(
                          e ->
                              log.atError()
                                  .setCause(e)
                                  .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                                  .log("Workflow execution error"))
                      .doOnTerminate(
                          () -> {
                            log.atTrace()
                                .addKeyValue(LOG_KEY_QUEUE_KEY, queueKey)
                                .log("Cleaning up queue entry");
                            workflowQueues.computeIfPresent(
                                queueKey,
                                (k, tail) -> {
                                  if (tail.equals(tailRef[0])) {
                                    log.atDebug()
                                        .addKeyValue(LOG_KEY_QUEUE_KEY, queueKey)
                                        .log("Removing queue entry");
                                    return null;
                                  }
                                  return tail;
                                });
                          })
                      .cache();
              return tailRef[0];
            })
        .subscribe();

    return new WorkflowExecution(executionId, sink.asMono());
  }
}
