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
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.gateway.WorkflowGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Executes a nested DAG as a single node in the parent DAG. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class SubWorkflowProcessor implements ProcessorPlugin {

  private static final String TYPE = "SUB_WORKFLOW";

  @Autowired private WorkflowGateway workflowGateway;

  /** Default constructor. */
  public SubWorkflowProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Executes a nested DAG (sub-workflow) as a single node in the parent DAG.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- subWorkflowId: The identifier of the workflow to execute.\n"
        + "- inputMapper: Optional SpEL expression to transform parent message into child "
        + "trigger payload.\n"
        + "- outputMapper: Optional SpEL expression to transform child execution results back "
        + "into the final node output.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex items-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl px-4 gap-3">
                <div class="flex-shrink-0 w-8 h-8 bg-slate-100 rounded-lg flex items-center justify-center text-slate-500">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
                    </svg>
                </div>
                <div class="flex flex-col min-w-0">
                    <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">SubFlow</div>
                    <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public Duration getDefaultTimeout() {
    return Duration.ofSeconds(3600);
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String inputMapper = (String) config.get("inputMapper");
    final String outputMapper = (String) config.get("outputMapper");
    SpelUtils.preParse(inputMapper);
    SpelUtils.preParse(outputMapper);
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String subWorkflowId = (String) config.get("subWorkflowId");
    final String inputMapper = (String) config.get("inputMapper");
    final String outputMapper = (String) config.get("outputMapper");

    if (subWorkflowId == null) {
      return Flux.error(new IllegalArgumentException("subWorkflowId is mandatory"));
    }

    return input.concatMap(
        parentMessage ->
            Mono.deferContextual(
                ctx -> {
                  final String parentSessionId = ctx.getOrDefault("sessionId", "unknown");
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  final String childSessionId = parentSessionId + ":" + nodeId;

                  return mapInput(parentMessage, inputMapper, parentSessionId)
                      .flatMap(
                          triggerPayload ->
                              executeSubWorkflow(
                                  parentSessionId, childSessionId, subWorkflowId, triggerPayload))
                      .flatMap(results -> mapOutput(results, outputMapper, parentMessage));
                }));
  }

  private Mono<Map<String, Object>> mapInput(
      final Message<?> message, final String mapper, final String sessionId) {
    if (mapper == null || mapper.isBlank()) {
      if (message.getPayload() instanceof Map) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        return Mono.just(payload);
      }
      return Mono.just(Map.of("payload", message.getPayload()));
    }
    return SpelUtils.evaluate(
        mapper, message, Map.of("headers", message.getMetadata(), "sessionId", sessionId));
  }

  private Mono<List<Message<?>>> executeSubWorkflow(
      final String parentSessionId,
      final String childSessionId,
      final String workflowId,
      final Map<String, Object> payload) {
    return workflowGateway.executeSubWorkflow(parentSessionId, childSessionId, workflowId, payload);
  }

  private Mono<Message<?>> mapOutput(
      final List<Message<?>> results, final String mapper, final Message<?> parentMessage) {
    if (mapper == null || mapper.isBlank()) {
      // Default: merge results into a single payload if possible, or just return them
      return Mono.just(DefaultMessage.from(parentMessage, results));
    }

    return SpelUtils.<Object>evaluate(mapper, results, Map.of("parentMessage", parentMessage))
        .map(payload -> DefaultMessage.from(parentMessage, payload));
  }
}
