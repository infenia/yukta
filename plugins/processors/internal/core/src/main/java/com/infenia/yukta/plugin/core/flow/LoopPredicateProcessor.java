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

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowRegistry;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executes a target plugin repeatedly until a condition is met. Emits only the final successful
 * message.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class LoopPredicateProcessor implements ProcessorPlugin {

  private static final String TYPE = "LOOP_PREDICATE";
  private static final String DEFAULT_TASK_ID = "default";

  @Autowired private ObjectProvider<WorkflowRegistry> registryProvider;

  @Autowired private ObjectProvider<TaskTrackerService> trackerProvider;

  /** Default constructor. */
  public LoopPredicateProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Executes a target plugin repeatedly until a condition is met. Emits only the final"
        + " successful message.";
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
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String exitCondition = (String) config.get("exitCondition");
    SpelUtils.preParse(exitCondition);
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String targetId = (String) config.get("targetPluginId");
    final WorkflowRegistry registry = registryProvider.getIfAvailable();

    if (registry == null || !registry.contains(targetId)) {
      return Flux.error(new IllegalArgumentException("Target plugin not found: " + targetId));
    }

    final WorkflowPlugin targetPlugin = registry.get(targetId);
    if (!(targetPlugin instanceof ProcessorPlugin processor)) {
      return Flux.error(
          new IllegalArgumentException("Target plugin must be a ProcessorPlugin: " + targetId));
    }

    final LoopContext context = LoopContext.from(config, processor);

    return input.concatMap(
        initialMessage ->
            Mono.deferContextual(
                ctx -> {
                  final String eId = ctx.getOrDefault("executionId", "unknown");
                  final String nId = ctx.getOrDefault("nodeId", "unknown");
                  final long start = System.currentTimeMillis();

                  return executeLoop(initialMessage, context, eId, nId, start);
                }));
  }

  private Mono<Message<?>> executeLoop(
      final Message<?> initialMessage,
      final LoopContext context,
      final String executionId,
      final String nodeId,
      final long startTime) {

    return Mono.just(new LoopState(initialMessage, 0, false, null))
        .expand(
            state -> {
              if (state.terminated()) {
                return Mono.empty();
              }

              if (state.iteration() >= context.maxIterations()) {
                return logAndTerminate(
                    executionId, nodeId, "Max iterations (" + context.maxIterations() + ")", state);
              }
              if (System.currentTimeMillis() - startTime > context.maxDuration().toMillis()) {
                return logAndTerminate(
                    executionId, nodeId, "Max duration (" + context.maxDuration() + ")", state);
              }

              return logIteration(executionId, nodeId, state.iteration() + 1)
                  .then(
                      context
                          .processor()
                          .process(Flux.just(state.message()), context.targetConfig())
                          .last())
                  .flatMap(
                      resultMessage -> {
                        final long elapsed = System.currentTimeMillis() - startTime;
                        return checkExit(
                                resultMessage,
                                context.exitCondition(),
                                state.iteration() + 1,
                                elapsed)
                            .flatMap(
                                exit -> {
                                  if (Boolean.TRUE.equals(exit)) {
                                    return logAndTerminate(
                                        executionId,
                                        nodeId,
                                        "Exit condition met",
                                        state.next(resultMessage, true));
                                  }
                                  return Mono.just(state.next(resultMessage, false))
                                      .delayElement(context.delayInterval());
                                });
                      })
                  .onErrorResume(
                      e ->
                          handleFailure(
                              e, context.failureStrategy(), state, context.delayInterval()));
            })
        .filter(LoopState::terminated)
        .last()
        .map(LoopState::message);
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
      case RETRY -> Mono.just(state.retry()).delayElement(delay);
      case SKIP -> Mono.just(state.next(state.message(), false)).delayElement(delay);
      case ESCALATE ->
          Mono.error(
              new RuntimeException("WorkflowExecutionException: Loop execution failed", error));
    };
  }

  private Mono<Void> logIteration(
      final String executionId, final String nodeId, final int iteration) {
    final TaskTrackerService tracker = trackerProvider.getIfAvailable();
    if (tracker == null) {
      return Mono.empty();
    }
    return tracker
        .appendLog(executionId, "[Loop] Node: " + nodeId + " - Iteration " + iteration)
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<LoopState> logAndTerminate(
      final String executionId, final String nodeId, final String reason, final LoopState state) {
    final TaskTrackerService tracker = trackerProvider.getIfAvailable();
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

  private record LoopState(Message<?> message, int iteration, boolean terminated, Throwable error) {
    /* default */ LoopState next(final Message<?> newMessage, final boolean term) {
      return new LoopState(newMessage, iteration + 1, term, null);
    }

    /* default */ LoopState retry() {
      return new LoopState(message, iteration + 1, false, null);
    }

    /* default */ LoopState withTerminated(final boolean term) {
      return new LoopState(message, iteration, term, null);
    }
  }
}
