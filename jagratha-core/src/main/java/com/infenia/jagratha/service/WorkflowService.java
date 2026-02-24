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
package com.infenia.jagratha.service;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.model.WorkflowExecution;
import com.infenia.jagratha.validation.SessionId;
import com.infenia.jagratha.validation.WorkflowId;
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
public class WorkflowService {

  private final AppConfigService configService;
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

    workflowQueues
        .compute(
            queueKey,
            (key, previousTail) -> {
              final Mono<Void> current =
                  Mono.defer(
                          () ->
                              configService
                                  .getWorkflow(sessionId, workflowId)
                                  .flatMap(
                                      def ->
                                          orchestrator
                                              .prepareWorkflow(def)
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
                                      Mono.just(
                                          new TaskResponse(
                                              "FAILURE",
                                              "No workflow configured with ID: " + workflowId)))
                                  .onErrorResume(
                                      e -> {
                                        if (log.isErrorEnabled()) {
                                          log.error(
                                              "Workflow "
                                                  + workflowId
                                                  + " failed for session: "
                                                  + sessionId,
                                              e);
                                        }
                                        return Mono.just(
                                            new TaskResponse(
                                                "FAILURE", "Workflow failed: " + e.getMessage()));
                                      })
                                  .flatMap(
                                      response -> {
                                        sink.tryEmitValue(response);
                                        return Mono.empty();
                                      })
                                  .then())
                      .subscribeOn(Schedulers.boundedElastic());

              final Mono<Void> nextTail;
              if (previousTail == null) {
                nextTail = current;
              } else {
                nextTail = previousTail.onErrorResume(e -> Mono.empty()).then(current);
              }
              return nextTail
                  .doOnTerminate(
                      () -> {
                        workflowQueues.computeIfPresent(
                            queueKey,
                            (k, tail) -> {
                              if (tail.equals(nextTail)) {
                                return null;
                              }
                              return tail;
                            });
                      })
                  .cache();
            })
        .subscribe();

    return new WorkflowExecution(executionId, sink.asMono());
  }
}
