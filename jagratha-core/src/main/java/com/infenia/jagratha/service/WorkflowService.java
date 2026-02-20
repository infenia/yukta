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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for orchestrating quality check workflows. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

  private final AppConfigService configService;
  private final WorkflowOrchestrator orchestrator;

  /**
   * Run quality checks using the configured DAG workflow.
   *
   * @param sessionId the session identifier
   * @return Mono containing the task response
   */
  public Mono<TaskResponse> runQualityChecks(final String sessionId) {
    return configService
        .getWorkflow(sessionId)
        .flatMap(
            def ->
                orchestrator
                    .prepareWorkflow(def)
                    .then(orchestrator.execute(sessionId, def))
                    .thenReturn(new TaskResponse("SUCCESS", "Workflow executed successfully")))
        .switchIfEmpty(
            Mono.just(new TaskResponse("FAILURE", "No workflow configured for this session.")))
        .onErrorResume(
            e -> {
              log.error("Workflow execution failed for session: {}", sessionId, e);
              return Mono.just(new TaskResponse("FAILURE", "Workflow failed: " + e.getMessage()));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }
}
