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
package com.infenia.yukta.ui;

import com.infenia.yukta.dto.response.PluginDetails;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

/** Controller for the Yukta UI. */
@SuppressWarnings("PMD.TooManyMethods")
@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class UiController {

  private static final String SESSION_ID_KEY = "sessionId";

  private final SessionService sessionService;
  private final TaskTrackerService tracker;
  private final PluginRegistry registry;
  private final ControlBusGateway controlBus;
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
        .getSessionIds()
        .flatMap(
            id ->
                sessionService
                    .getSessionConfig(id)
                    .map(
                        config -> {
                          final Map<String, Object> map = new ConcurrentHashMap<>(config);
                          map.put(SESSION_ID_KEY, id);
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
        .getSessionIds()
        .flatMap(
            id ->
                sessionService
                    .getSessionConfig(id)
                    .map(
                        config -> {
                          final Map<String, Object> map = new ConcurrentHashMap<>(config);
                          map.put(SESSION_ID_KEY, id);
                          return map;
                        }))
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
   * Render the control bus page.
   *
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(value = "/control", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> control(final Model model) {
    return Mono.fromCallable(controlBus::getActiveNodes)
        .flatMap(
            nodes -> {
              model.addAttribute("nodes", nodes);
              return Mono.fromCallable(
                      () -> {
                        final StringOutput output = new StringOutput();
                        templateEngine.render("control-bus.jte", model.asMap(), output);
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

    return sessionService
        .getSessionConfig(sessionId)
        .flatMap(
            config -> {
              model.addAttribute("config", config);
              model.addAttribute("logs", java.util.List.of());

              final Object workflowsObj = config.get("workflows");
              model.addAttribute(
                  "workflows", workflowsObj instanceof Map ? workflowsObj : Map.of());
              final String actualWorkflowId = resolveWorkflowId(workflowId, workflowsObj);

              addProgressToModel(sessionId, actualWorkflowId, model);
              return fetchAndRenderSession(sessionId, actualWorkflowId, model);
            });
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private String resolveWorkflowId(final String workflowId, final Object workflowsObj) {
    if (workflowId != null) {
      return workflowId;
    }
    return workflowsObj instanceof Map workflows && !workflows.isEmpty()
        ? (String) workflows.keySet().iterator().next()
        : null;
  }

  private void addProgressToModel(
      final String sessionId, final String workflowId, final Model model) {
    if (workflowId != null) {
      final String execId = tracker.getLatestExecutionId(sessionId, workflowId);
      model.addAttribute(
          "progress", execId != null ? tracker.getProgress(sessionId, execId) : null);
    } else {
      model.addAttribute("progress", null);
    }
  }

  private Mono<String> fetchAndRenderSession(
      final String sessionId, final String workflowId, final Model model) {
    final Mono<WorkflowDefinition> workflowMono =
        workflowId != null
            ? sessionService
                .getSessionWorkflow(sessionId, workflowId)
                .defaultIfEmpty(
                    new WorkflowDefinition(
                        workflowId, "No workflow found", java.util.List.of(), java.util.List.of()))
            : Mono.just(
                new WorkflowDefinition(
                    workflowId, "No workflow defined", java.util.List.of(), java.util.List.of()));

    return workflowMono.flatMap(workflow -> renderSessionTemplate(workflow, workflowId, model));
  }

  private Mono<String> renderSessionTemplate(
      final WorkflowDefinition workflow, final String workflowId, final Model model) {
    model.addAttribute("workflow", workflow);
    model.addAttribute("actualWorkflowId", workflowId);

    final Map<String, PluginDetails> pluginDetails =
        workflow.nodes().stream()
            .map(WorkflowDefinition.Node::type)
            .distinct()
            .map(registry::get)
            .filter(java.util.Objects::nonNull)
            .collect(
                Collectors.toMap(
                    Plugin::getType,
                    p ->
                        new PluginDetails(
                            p.getType(),
                            p.getCategory(),
                            p.getDescription(),
                            p.getUsagePattern(),
                            p.getUiDesign().orElse(null),
                            p.getOutputPorts(
                                workflow.nodes().stream()
                                    .filter(n -> n.type().equals(p.getType()))
                                    .findFirst()
                                    .map(WorkflowDefinition.Node::config)
                                    .orElse(Map.of())))));
    model.addAttribute("pluginDetails", pluginDetails);

    return Mono.fromCallable(
            () -> {
              final StringOutput output = new StringOutput();
              templateEngine.render("session.jte", model.asMap(), output);
              return output.toString();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Render the workflow detail page.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(
      value = "/sessions/{sessionId}/workflow/{workflowId}",
      produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> workflow(
      @PathVariable final String sessionId,
      @PathVariable final String workflowId,
      final Model model) {
    model.addAttribute("sessionId", sessionId);
    model.addAttribute("workflowId", workflowId);

    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .flatMap(
            workflow -> {
              model.addAttribute("workflow", workflow);
              model.addAttribute("logs", java.util.List.of());

              final String execId = tracker.getLatestExecutionId(sessionId, workflowId);
              model.addAttribute(
                  "progress", execId != null ? tracker.getProgress(sessionId, execId) : null);

              final Map<String, PluginDetails> pluginDetails =
                  workflow.nodes().stream()
                      .map(WorkflowDefinition.Node::type)
                      .distinct()
                      .map(registry::get)
                      .filter(java.util.Objects::nonNull)
                      .collect(
                          Collectors.toMap(
                              Plugin::getType,
                              p ->
                                  new PluginDetails(
                                      p.getType(),
                                      p.getCategory(),
                                      p.getDescription(),
                                      p.getUsagePattern(),
                                      p.getUiDesign().orElse(null),
                                      p.getOutputPorts(
                                          workflow.nodes().stream()
                                              .filter(n -> n.type().equals(p.getType()))
                                              .findFirst()
                                              .map(WorkflowDefinition.Node::config)
                                              .orElse(Map.of())))));
              model.addAttribute("pluginDetails", pluginDetails);

              return Mono.fromCallable(
                      () -> {
                        final String pluginDetailsJson = buildPluginDetailsJson(pluginDetails);
                        model.addAttribute("pluginDetailsJson", pluginDetailsJson);
                        final StringOutput output = new StringOutput();
                        templateEngine.render("workflow.jte", model.asMap(), output);
                        return output.toString();
                      })
                  .subscribeOn(Schedulers.boundedElastic());
            });
  }

  /**
   * Stream logs for a session via SSE.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a flux of log lines formatted as HTML
   */
  @GetMapping(
      value = "/api/sessions/{sessionId}/{workflowId}/logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<String> streamLogs(
      @PathVariable final String sessionId, @PathVariable final String workflowId) {
    final String execId = tracker.getLatestExecutionId(sessionId, workflowId);
    final Flux<String> result;
    if (execId != null) {
      final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
      result =
          tracker
              .getLogStream(execId)
              .map(
                  log -> {
                    final String time = LocalTime.now(ZoneId.systemDefault()).format(formatter);
                    return "<div class=\"mb-1 text-slate-300 flex gap-4\">"
                        + "<span class=\"opacity-20 w-16 shrink-0\">"
                        + time
                        + "</span>"
                        + "<span class=\"text-indigo-400\">➜</span>"
                        + "<span>"
                        + log
                        + "</span></div>";
                  });
    } else {
      result = Flux.empty();
    }
    return result;
  }

  /**
   * Stream status updates for a session via SSE.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a flux of status update events formatted as HTML
   */
  @GetMapping(
      value = "/api/sessions/{sessionId}/{workflowId}/status/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<String> streamStatus(
      @PathVariable final String sessionId, @PathVariable final String workflowId) {
    final String execId = tracker.getLatestExecutionId(sessionId, workflowId);
    return execId != null
        ? tracker
            .getStatusStream(execId)
            .map(
                progress -> {
                  final String status = progress.status();
                  return "<span class=\"px-5 py-2 rounded-xl text-xs font-display font-bold"
                      + " uppercase tracking-widest glass-panel text-primary"
                      + " border-primary/20 animate-pulse\">"
                      + status
                      + "</span>";
                })
        : Flux.empty();
  }

  private String buildPluginDetailsJson(final Map<String, PluginDetails> pluginDetails) {
    final StringBuilder json = new StringBuilder(512);
    final int openBraceLen = 1;
    json.append('{');
    pluginDetails.forEach(
        (key, plugin) -> {
          if (json.length() > openBraceLen) {
            json.append(',');
          }
          json.append('\"')
              .append(escapeJson(key))
              .append("\":{\"type\":\"")
              .append(escapeJson(plugin.type()))
              .append("\",");

          if (plugin.uiDesign() != null) {
            json.append("\"uiDesign\":{\"html\":\"")
                .append(escapeJson(plugin.uiDesign().html()))
                .append("\",\"width\":")
                .append(plugin.uiDesign().width())
                .append(",\"height\":")
                .append(plugin.uiDesign().height())
                .append('}');
          } else {
            json.append("\"uiDesign\":null");
          }
          json.append('}');
        });
    json.append('}');
    return json.toString();
  }

  private String escapeJson(final String str) {
    return str == null
        ? ""
        : str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
  }
}
