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
package com.infenia.jagratha.ui;

import com.infenia.jagratha.model.PluginDetails;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import com.infenia.jagratha.service.WorkflowRegistry;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Controller for the Jagratha UI. */
@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class UiController {

  private final SessionService sessionService;
  private final LogRetrievalService retrievalService;
  private final TaskTrackerService tracker;
  private final WorkflowRegistry registry;
  private final TemplateEngine templateEngine;

  /**
   * Render the index page.
   *
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> index(final Model model) {
    return sessionService
        .getActiveSessions()
        .flatMap(
            id ->
                sessionService
                    .getSessionConfig(id)
                    .map(
                        config -> {
                          final Map<String, Object> map = new ConcurrentHashMap<>(config);
                          map.put("sessionId", id);
                          return map;
                        }))
        .collectList()
        .flatMap(
            sessions -> {
              model.addAttribute("sessions", sessions);
              return Mono.fromCallable(
                      () -> {
                        final StringOutput output = new StringOutput();
                        templateEngine.render("index.jte", model.asMap(), output);
                        return output.toString();
                      })
                  .subscribeOn(Schedulers.boundedElastic());
            });
  }

  /**
   * Render the history page.
   *
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(value = "/history", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> history(final Model model) {
    return sessionService
        .getHistorySessions()
        .collectList()
        .flatMap(
            sessions -> {
              model.addAttribute("sessions", sessions);
              return Mono.fromCallable(
                      () -> {
                        final StringOutput output = new StringOutput();
                        templateEngine.render("history.jte", model.asMap(), output);
                        return output.toString();
                      })
                  .subscribeOn(Schedulers.boundedElastic());
            });
  }

  /**
   * Render the session detail page.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier (optional)
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(
      value = {"/sessions/{sessionId}", "/sessions/{sessionId}/{workflowId}"},
      produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> session(
      @PathVariable final String sessionId,
      @PathVariable(required = false) final String workflowId,
      final Model model) {
    model.addAttribute("sessionId", sessionId);
    model.addAttribute("selectedWorkflowId", workflowId);

    return Mono.zip(
            sessionService.getSessionConfig(sessionId), retrievalService.listLogs(sessionId))
        .flatMap(
            tuple -> {
              final Map<String, Object> config = tuple.getT1();
              model.addAttribute("config", config);
              model.addAttribute("logs", tuple.getT2());

              final Object workflowsObj = config.get("workflows");
              final String actualWorkflowId;
              if (workflowId != null) {
                actualWorkflowId = workflowId;
              } else if (workflowsObj instanceof Map workflows && !workflows.isEmpty()) {
                actualWorkflowId = (String) workflows.keySet().iterator().next();
              } else {
                actualWorkflowId = null;
              }

              if (actualWorkflowId != null) {
                final String execId = tracker.getLatestExecutionId(sessionId, actualWorkflowId);
                model.addAttribute(
                    "progress", execId != null ? tracker.getProgress(sessionId, execId) : null);
              } else {
                model.addAttribute("progress", null);
              }

              final Mono<com.infenia.jagratha.model.WorkflowDefinition> workflowMono;
              if (actualWorkflowId != null) {
                workflowMono =
                    sessionService
                        .getSessionWorkflow(sessionId, actualWorkflowId)
                        .defaultIfEmpty(
                            new com.infenia.jagratha.model.WorkflowDefinition(
                                "No workflow found", java.util.List.of(), java.util.List.of()));
              } else {
                workflowMono =
                    Mono.just(
                        new com.infenia.jagratha.model.WorkflowDefinition(
                            "No workflow defined", java.util.List.of(), java.util.List.of()));
              }

              return workflowMono.flatMap(
                  workflow -> {
                    model.addAttribute("workflow", workflow);
                    model.addAttribute("actualWorkflowId", actualWorkflowId);

                    final Map<String, PluginDetails> pluginDetails =
                        workflow.nodes().stream()
                            .map(com.infenia.jagratha.model.WorkflowDefinition.Node::type)
                            .distinct()
                            .map(registry::get)
                            .filter(java.util.Objects::nonNull)
                            .collect(
                                Collectors.toMap(
                                    com.infenia.jagratha.plugin.WorkflowPlugin::getType,
                                    p ->
                                        new PluginDetails(
                                            p.getType(),
                                            p.getCategory(),
                                            p.getDescription(),
                                            p.getUsagePattern(),
                                            p.getUiDesign().orElse(null),
                                            p.getOutputPorts())));
                    model.addAttribute("pluginDetails", pluginDetails);

                    return Mono.fromCallable(
                            () -> {
                              final StringOutput output = new StringOutput();
                              templateEngine.render("session.jte", model.asMap(), output);
                              return output.toString();
                            })
                        .subscribeOn(Schedulers.boundedElastic());
                  });
            });
  }

  /**
   * Stream logs for a session via SSE.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a flux of log lines
   */
  @GetMapping(
      value = "/api/sessions/{sessionId}/{workflowId}/logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<String> streamLogs(
      @PathVariable final String sessionId, @PathVariable final String workflowId) {
    final String execId = tracker.getLatestExecutionId(sessionId, workflowId);
    return execId != null ? tracker.getLogStream(execId) : Flux.empty();
  }

  /**
   * Stream status updates for a session via SSE.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a flux of status update events
   */
  @GetMapping(
      value = "/api/sessions/{sessionId}/{workflowId}/status/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<String> streamStatus(
      @PathVariable final String sessionId, @PathVariable final String workflowId) {
    final String execId = tracker.getLatestExecutionId(sessionId, workflowId);
    return execId != null
        ? tracker.getStatusStream(execId).map(progress -> "update")
        : Flux.empty();
  }
}
