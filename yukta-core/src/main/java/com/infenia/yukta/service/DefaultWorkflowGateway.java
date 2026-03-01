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

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.gateway.WorkflowGateway;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default implementation of the {@link WorkflowGateway} that interacts with internal services and
 * the orchestrator to execute workflows.
 */
@Slf4j
@Service
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class DefaultWorkflowGateway implements WorkflowGateway {

  private final ObjectProvider<WorkflowOrchestrator> orchProv;
  private final ObjectProvider<AppConfigService> cfgServProv;

  /**
   * Constructor for DefaultWorkflowGateway.
   *
   * @param orchProv provider for WorkflowOrchestrator
   * @param cfgServProv provider for AppConfigService
   */
  public DefaultWorkflowGateway(
      final ObjectProvider<WorkflowOrchestrator> orchProv,
      final ObjectProvider<AppConfigService> cfgServProv) {
    this.orchProv = orchProv;
    this.cfgServProv = cfgServProv;
  }

  @Override
  public Mono<List<Message<?>>> executeSubWorkflow(
      final String parentSessionId,
      final String childSessionId,
      final String workflowId,
      final Map<String, Object> payload) {

    final WorkflowOrchestrator orchestrator = orchProv.getIfAvailable();
    final AppConfigService configService = cfgServProv.getIfAvailable();

    if (orchestrator == null || configService == null) {
      return Mono.error(
          new WorkflowExecutionException("Required services (Orchestrator/Config) not available"));
    }

    final ResultCollector collector = new ResultCollector();

    return configService
        .getWorkflow(parentSessionId, workflowId)
        .switchIfEmpty(
            Mono.error(new WorkflowExecutionException("Workflow not found: " + workflowId)))
        .flatMap(
            def ->
                configService
                    .getProjectPath(parentSessionId)
                    .flatMap(path -> configService.setProjectPath(childSessionId, path))
                    .then(
                        configService
                            .getWorkflows(parentSessionId)
                            .flatMap(wfs -> configService.setWorkflows(childSessionId, wfs)))
                    .then(
                        configService
                            .getInitiator(parentSessionId)
                            .flatMap(init -> configService.setInitiator(childSessionId, init)))
                    .then(
                        configService
                            .getDescription(parentSessionId)
                            .flatMap(
                                desc ->
                                    configService.setDescription(
                                        childSessionId, desc + " (Sub-workflow)")))
                    .then(orchestrator.prepareWorkflow(def))
                    .flatMap(
                        prepared -> {
                          final String executionId = UUID.randomUUID().toString();
                          return orchestrator
                              .execute(childSessionId, workflowId, executionId, prepared, payload)
                              .contextWrite(ctx -> ctx.put("resultCollector", collector))
                              .then(Mono.fromCallable(collector::getResults));
                        }))
        .onErrorMap(
            e -> {
              if (e instanceof WorkflowExecutionException) {
                return e;
              }
              return new WorkflowExecutionException(
                  "Sub-workflow execution failed for " + workflowId + ": " + e.getMessage(), e);
            });
  }

  @Override
  public <T, R> Mono<Message<R>> sendAndReceive(final Message<T> request) {
    // Basic implementation that executes a sub-workflow if workflowId is in headers
    final String workflowId = (String) request.getMetadata().get("workflowId");
    if (workflowId == null) {
      return Mono.error(
          new WorkflowExecutionException("workflowId is required for gateway sendAndReceive"));
    }

    final String childSessionId = UUID.randomUUID().toString();
    @SuppressWarnings("unchecked")
    final Map<String, Object> payload =
        request.getPayload() instanceof Map
            ? (Map<String, Object>) request.getPayload()
            : Map.of("payload", request.getPayload());

    return executeSubWorkflow("gateway", childSessionId, workflowId, payload)
        .map(results -> (Message<R>) request.withPayload(results));
  }

  @Override
  public <T> Mono<Void> send(final Message<T> request) {
    return sendAndReceive(request).then();
  }

  @Override
  public <R> Flux<Message<R>> receive() {
    return Flux.empty();
  }
}
