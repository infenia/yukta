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

import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.util.SpelUtils;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Executes a target plugin repeatedly and flattens all produced messages into a single stream. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class LoopStreamProcessor implements ProcessorPlugin {

  private static final String TYPE = "LOOP_STREAM";

  @Autowired private ObjectProvider<PluginRegistry> registryProvider;

  @Autowired private ObjectProvider<DefaultTaskTrackerService> trackerProvider;

  /** Default constructor. */
  public LoopStreamProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Executes a target plugin repeatedly and flattens all produced messages into a single"
        + " stream.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- targetPluginId: The ID of the processor to execute in each iteration.\n"
        + "- targetConfig: Map containing configuration for the target processor.\n"
        + "- exitCondition: SpEL expression to terminate the loop (variables: #iterationCount, "
        + "#elapsedTimeMs).\n"
        + "- maxIterations: Maximum number of iterations (default: 10).\n"
        + "- maxDuration: ISO-8601 duration string (default: PT1M).\n"
        + "- delayInterval: ISO-8601 duration string to wait between iterations (default: PT0S).\n"
        + "- failureStrategy: 'ABORT', 'RETRY', 'SKIP', or 'ESCALATE'. Default is 'ABORT'.";
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
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                </div>
                <div class="flex flex-col min-w-0">
                    <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Loop</div>
                    <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String exitCondition = (String) config.get("exitCondition");
    SpelUtils.preParse(exitCondition);
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String targetId = (String) config.get("targetPluginId");
    final PluginRegistry registry = registryProvider.getIfAvailable();

    if (registry == null || !registry.contains(targetId)) {
      return Flux.error(new IllegalArgumentException("Target plugin not found: " + targetId));
    }

    final Plugin targetPlugin = registry.get(targetId);
    if (!(targetPlugin instanceof ProcessorPlugin processor)) {
      return Flux.error(
          new IllegalArgumentException("Target plugin must be a ProcessorPlugin: " + targetId));
    }

    final LoopContext context = LoopContext.from(config, processor);

    return input.concatMap(
        initialMessage ->
            Flux.deferContextual(
                ctx -> {
                  final String eId = ctx.getOrDefault("executionId", "unknown");
                  final String nId = ctx.getOrDefault("nodeId", "unknown");
                  final long start = System.currentTimeMillis();

                  return executeLoop(initialMessage, context, eId, nId, start);
                }));
  }

  private Flux<Message<?>> executeLoop(
      final Message<?> initialMessage,
      final LoopContext context,
      final String executionId,
      final String nodeId,
      final long startTime) {

    return Flux.just(new LoopState(List.of(), initialMessage, 0, false))
        .expand(
            state -> {
              if (state.terminated()) {
                return Mono.empty();
              }

              if (state.iteration() >= context.maxIterations()) {
                return logAndTerminate(
                    executionId,
                    nodeId,
                    "Max iterations (" + context.maxIterations() + ")",
                    new LoopState(List.of(), state.lastMessage(), state.iteration(), false));
              }
              if (System.currentTimeMillis() - startTime > context.maxDuration().toMillis()) {
                return logAndTerminate(
                    executionId,
                    nodeId,
                    "Max duration (" + context.maxDuration() + ")",
                    new LoopState(List.of(), state.lastMessage(), state.iteration(), false));
              }

              return logIteration(executionId, nodeId, state.iteration() + 1)
                  .thenMany(
                      context
                          .processor()
                          .process(Flux.just(state.lastMessage()), context.targetConfig()))
                  .collectList()
                  .flatMap(
                      messages -> {
                        final Message<?> lastMsg =
                            messages.isEmpty()
                                ? state.lastMessage()
                                : messages.get(messages.size() - 1);
                        final long elapsed = System.currentTimeMillis() - startTime;
                        return checkExit(
                                lastMsg, context.exitCondition(), state.iteration() + 1, elapsed)
                            .flatMap(
                                exit -> {
                                  if (Boolean.TRUE.equals(exit)) {
                                    return logAndTerminate(
                                        executionId,
                                        nodeId,
                                        "Exit condition met",
                                        new LoopState(
                                            messages, lastMsg, state.iteration() + 1, false));
                                  }
                                  return Mono.just(
                                          new LoopState(
                                              messages, lastMsg, state.iteration() + 1, false))
                                      .delayElement(context.delayInterval());
                                });
                      })
                  .onErrorResume(
                      e ->
                          handleFailure(
                              e, context.failureStrategy(), state, context.delayInterval()));
            })
        .concatMapIterable(LoopState::messages);
  }

  private Mono<Boolean> checkExit(
      final Message<?> message, final String condition, final int iteration, final long elapsed) {
    if (condition == null || condition.isBlank()) {
      return Mono.just(true);
    }
    final Map<String, Object> vars =
        Map.of(
            "iterationCount", iteration,
            "elapsedTimeMs", elapsed);
    return SpelUtils.evaluate(condition, message, vars);
  }

  private Mono<LoopState> handleFailure(
      final Throwable error,
      final FailureStrategy strategy,
      final LoopState state,
      final Duration delay) {
    return switch (strategy) {
      case ABORT -> Mono.error(error);
      case RETRY ->
          Mono.just(new LoopState(List.of(), state.lastMessage(), state.iteration() + 1, false))
              .delayElement(delay);
      case SKIP ->
          Mono.just(new LoopState(List.of(), state.lastMessage(), state.iteration() + 1, false))
              .delayElement(delay);
      case ESCALATE ->
          Mono.error(
              new RuntimeException("WorkflowExecutionException: Loop execution failed", error));
    };
  }

  private Mono<Void> logIteration(
      final String executionId, final String nodeId, final int iteration) {
    final DefaultTaskTrackerService tracker = trackerProvider.getIfAvailable();
    if (tracker == null) {
      return Mono.empty();
    }
    return tracker
        .appendLog(executionId, "[Loop] Node: " + nodeId + " - Iteration " + iteration)
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<LoopState> logAndTerminate(
      final String executionId, final String nodeId, final String reason, final LoopState state) {
    final DefaultTaskTrackerService tracker = trackerProvider.getIfAvailable();
    final LoopState termState = state.withTerminated(true);
    if (tracker == null) {
      return Mono.just(termState);
    }
    return tracker
        .appendLog(executionId, "[Loop] Node: " + nodeId + " - " + reason)
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.just(termState));
  }

  private record LoopContext(
      ProcessorPlugin processor,
      Map<String, Object> targetConfig,
      int maxIterations,
      Duration maxDuration,
      Duration delayInterval,
      String exitCondition,
      FailureStrategy failureStrategy) {

    @SuppressWarnings("unchecked")
    /* default */ static LoopContext from(
        final Map<String, Object> config, final ProcessorPlugin processor) {
      return new LoopContext(
          processor,
          (Map<String, Object>) config.getOrDefault("targetConfig", Map.of()),
          ((Number) config.getOrDefault("maxIterations", 10)).intValue(),
          Duration.parse((String) config.getOrDefault("maxDuration", "PT1M")),
          Duration.parse((String) config.getOrDefault("delayInterval", "PT0S")),
          (String) config.get("exitCondition"),
          FailureStrategy.valueOf(
              ((String) config.getOrDefault("failureStrategy", "ABORT")).toUpperCase(Locale.ROOT)));
    }
  }

  private record LoopState(
      List<Message<?>> messages, Message<?> lastMessage, int iteration, boolean terminated) {
    /* default */ LoopState withTerminated(final boolean term) {
      return new LoopState(messages, lastMessage, iteration, term);
    }
  }
}
