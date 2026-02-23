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
package com.infenia.jagratha.plugin.loop;

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.service.TaskTrackerService;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Base class for iterative processors. Handles loop logic, exit criteria, and telemetry. */
@Slf4j
@SuppressWarnings("PMD.OnlyOneReturn")
public abstract class LoopProcessor implements ProcessorPlugin {

  private static final String DEFAULT_TASK_ID = "default";
  private static final String STATUS_SUCCESS = "SUCCESS";

  @Autowired protected TaskTrackerService tracker;

  /** Default constructor. */
  protected LoopProcessor() {
    super();
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    try {
      if (config.containsKey("maxDuration")) {
        final String duration = (String) config.get("maxDuration");
        if (Duration.parse(duration) == null) {
          return Mono.error(new IllegalArgumentException("Invalid maxDuration"));
        }
      }
      if (config.containsKey("delayInterval")) {
        final String delay = (String) config.get("delayInterval");
        if (Duration.parse(delay) == null) {
          return Mono.error(new IllegalArgumentException("Invalid delayInterval"));
        }
      }
      if (config.containsKey("failureStrategy")) {
        final String strategy = (String) config.get("failureStrategy");
        FailureStrategy.valueOf(strategy.toUpperCase(java.util.Locale.ROOT));
      }
      return Mono.empty();
    } catch (DateTimeParseException | IllegalArgumentException e) {
      return Mono.error(new IllegalArgumentException("Invalid configuration: " + e.getMessage()));
    }
  }

  @Override
  public Flux<Message> process(final Flux<Message> input, final Map<String, Object> config) {
    final int maxIterations = ((Number) config.getOrDefault("maxIterations", 10)).intValue();
    final Duration maxDuration =
        Duration.parse((String) config.getOrDefault("maxDuration", "PT1M"));
    final Duration delayInterval =
        Duration.parse((String) config.getOrDefault("delayInterval", "PT0S"));
    final FailureStrategy strategy =
        FailureStrategy.valueOf(
            ((String) config.getOrDefault("failureStrategy", "ABORT"))
                .toUpperCase(java.util.Locale.ROOT));
    final boolean emitIntermediate = (boolean) config.getOrDefault("emitIntermediate", false);

    return input.concatMap(
        initialMessage ->
            Flux.deferContextual(
                ctx -> {
                  final String sessionId = ctx.getOrDefault("sessionId", "unknown");
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  final long startTime = System.currentTimeMillis();

                  final LoopParams params =
                      new LoopParams(
                          config,
                          maxIterations,
                          maxDuration,
                          delayInterval,
                          strategy,
                          emitIntermediate,
                          sessionId,
                          nodeId,
                          startTime);

                  return executeLoop(initialMessage, params);
                }));
  }

  @SuppressWarnings("PMD.CognitiveComplexity")
  private Flux<Message> executeLoop(final Message initialMessage, final LoopParams params) {

    final Flux<LoopState> loopFlux =
        Mono.just(
                new LoopState(
                    initialMessage,
                    0,
                    params.startTime(),
                    params.sessionId(),
                    params.nodeId(),
                    false))
            .expand(
                state -> {
                  if (state.terminated()) {
                    return Mono.empty();
                  }

                  if (state.iterationCount() >= params.maxIterations()) {
                    return logAndTerminate(
                        state, "Max iterations reached (" + params.maxIterations() + ")");
                  }
                  if (System.currentTimeMillis() - state.startTime()
                      >= params.maxDuration().toMillis()) {
                    return logAndTerminate(
                        state, "Max duration reached (" + params.maxDuration() + ")");
                  }

                  return checkExitCondition(state.message(), params.config())
                      .flatMap(
                          exit -> {
                            if (Boolean.TRUE.equals(exit)) {
                              return logAndTerminate(state, "Exit condition met");
                            }
                            return executeIterationWithStrategy(state, params);
                          });
                });

    final Flux<LoopState> filteredFlux =
        params.emitIntermediate()
            ? loopFlux.filter(s -> s.iterationCount() > 0 || s.terminated())
            : loopFlux.takeLast(1);

    return filteredFlux.concatMap(
        state -> {
          if (state.terminated()) {
            final long executionTime = System.currentTimeMillis() - params.startTime();
            final Map<String, Object> metadata =
                Map.of("totalIterations", state.iterationCount(), "executionTimeMs", executionTime);
            final Mono<Void> updateStatus =
                tracker
                    .updateTaskStatus(
                        params.sessionId(),
                        params.nodeId(),
                        DEFAULT_TASK_ID,
                        STATUS_SUCCESS,
                        metadata)
                    .subscribeOn(Schedulers.boundedElastic());

            if (!params.emitIntermediate() || state.iterationCount() == 0) {
              return updateStatus.then(Mono.just(state.message()));
            } else {
              return updateStatus.then(Mono.empty());
            }
          }
          return Mono.just(state.message());
        });
  }

  private Mono<LoopState> logAndTerminate(final LoopState state, final String reason) {
    return Mono.defer(
        () ->
            tracker
                .appendLog(state.sessionId(), "[Loop] Node: " + state.nodeId() + " - " + reason)
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(state.withTerminated(true))));
  }

  private Mono<LoopState> executeIterationWithStrategy(
      final LoopState state, final LoopParams params) {

    return tracker
        .appendLog(
            state.sessionId(),
            "[Loop] Node: " + state.nodeId() + " - Iteration " + (state.iterationCount() + 1))
        .subscribeOn(Schedulers.boundedElastic())
        .then(
            doIteration(state.message(), params.config())
                .map(state::next)
                .onErrorResume(
                    e -> {
                      log.error("Error during loop iteration", e);
                      return switch (params.strategy()) {
                        case ABORT -> Mono.error(e);
                        case RETRY_CURRENT -> Mono.just(state.retry());
                        case SKIP -> Mono.just(state.next(state.message()));
                      };
                    }))
        .delayElement(params.delayInterval());
  }

  /**
   * Perform one iteration of the loop.
   *
   * @param message the current message
   * @param config the plugin configuration
   * @return a Mono emitting the transformed message
   */
  protected abstract Mono<Message> doIteration(Message message, Map<String, Object> config);

  /**
   * Check if the loop should exit based on the current message.
   *
   * @param message the current message
   * @param config the plugin configuration
   * @return a Mono emitting true if the loop should exit
   */
  protected abstract Mono<Boolean> checkExitCondition(Message message, Map<String, Object> config);

  /** Failure strategies for loop iterations. */
  public enum FailureStrategy {
    ABORT,
    RETRY_CURRENT,
    SKIP
  }

  private record LoopParams(
      Map<String, Object> config,
      int maxIterations,
      Duration maxDuration,
      Duration delayInterval,
      FailureStrategy strategy,
      boolean emitIntermediate,
      String sessionId,
      String nodeId,
      long startTime) {}

  private record LoopState(
      Message message,
      int iterationCount,
      long startTime,
      String sessionId,
      String nodeId,
      boolean terminated) {
    /* default */ LoopState next(final Message newMessage) {
      return new LoopState(newMessage, iterationCount + 1, startTime, sessionId, nodeId, false);
    }

    /* default */ LoopState retry() {
      return new LoopState(message, iterationCount + 1, startTime, sessionId, nodeId, false);
    }

    /* default */ LoopState withTerminated(final boolean term) {
      return new LoopState(message, iterationCount, startTime, sessionId, nodeId, term);
    }
  }
}
