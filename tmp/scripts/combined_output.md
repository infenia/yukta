---
### BranchProcessor.java
Location: `java/com/infenia/yukta/plugin/core/BranchProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.NoMatchingBranchException;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Branch plugin routes messages to different ports based on conditions or selectors. Supports
 * SELECT_KEY (exact match) and EXPRESSION (SpEL predicate) modes.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.CognitiveComplexity",
  "PMD.AvoidDeeplyNestedIfStmts",
  "PMD.ExceptionAsFlowControl",
  "PMD.AvoidCatchingGenericException"
})
public class BranchProcessor implements ProcessorPlugin {

  private static final String TYPE = "BRANCH";

  private static final String CONFIG_MODE = "mode";
  private static final String CONFIG_SELECTOR = "selector";
  private static final String CONFIG_CASES = "cases";
  private static final String DEF_PORT = "defaultPort";
  private static final String ALLOW_MULT = "allowMultipleMatches";
  private static final String STRICT = "strictMode";

  private static final String MODE_SELECT_KEY = "SELECT_KEY";
  private static final String MODE_EXPRESSION = "EXPRESSION";

  private static final String ERR_PREFIX = "WorkflowExecutionException: ";

  /** Default constructor. */
  public BranchProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Routes messages to different ports based on conditions or selectors. Supports exact"
        + " match and SpEL predicate modes.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'SELECT_KEY' (evaluates selector and looks up in cases) or 'EXPRESSION' "
        + "(evaluates each case key as a predicate).\n"
        + "- selector: SpEL expression used in 'SELECT_KEY' mode.\n"
        + "- cases: Map where keys are match values (SELECT_KEY) or SpEL predicates (EXPRESSION), "
        + "and values are output port names.\n"
        + "- defaultPort: Optional port name if no matches are found.\n"
        + "- allowMultipleMatches: Boolean. If true, message can be routed to multiple ports. "
        + "Default is false.\n"
        + "- strictMode: Boolean. If true (default), throws exception if no branch matches and "
        + "no defaultPort is set.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (MODE_SELECT_KEY.equals(mode)) {
      SpelUtils.preParse((String) config.get(CONFIG_SELECTOR));
    } else if (MODE_EXPRESSION.equals(mode)) {
      final Map<String, String> cases = (Map<String, String>) config.get(CONFIG_CASES);
      if (cases != null) {
        cases.keySet().forEach(SpelUtils::preParse);
      }
    }
    return Mono.empty();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final String selector = (String) config.get(CONFIG_SELECTOR);
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);
    final boolean allowMultiple = (Boolean) config.getOrDefault(ALLOW_MULT, false);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);

    return input.flatMap(
        message -> {
          final List<String> matchedPorts = new ArrayList<>();

          try {
            evaluateBranches(mode, selector, cases, allowMultiple, message, matchedPorts);

            if (matchedPorts.isEmpty()) {
              if (defaultPort != null) {
                matchedPorts.add(defaultPort);
              } else if (strictMode) {
                throw new NoMatchingBranchException(
                    ERR_PREFIX
                        + "No matching branch found for message "
                        + message.getMessageId()
                        + " and no default port configured");
              }
            }

            return Flux.fromIterable(matchedPorts).map(message::withSourcePort);

          } catch (final Exception e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Branch evaluation failed for message {}: {}",
                  message.getMessageId(),
                  e.getMessage());
            }
            return Flux.error(
                e.getMessage() != null && e.getMessage().startsWith(ERR_PREFIX)
                    ? e
                    : new RuntimeException(ERR_PREFIX + "Branch evaluation failed", e));
          }
        });
  }

  private void evaluateBranches(
      final String mode,
      final String selector,
      final Map<String, String> cases,
      final boolean allowMultiple,
      final Message<?> message,
      final List<String> matchedPorts) {
    if (MODE_SELECT_KEY.equals(mode)) {
      final Object result = SpelUtils.evaluateSync(selector, message);
      if (result != null) {
        final String port = cases.get(result.toString());
        if (port != null) {
          matchedPorts.add(port);
        }
      }
    } else if (MODE_EXPRESSION.equals(mode)) {
      for (final Map.Entry<String, String> entry : cases.entrySet()) {
        final Boolean match = SpelUtils.evaluateSync(entry.getKey(), message);
        if (Boolean.TRUE.equals(match)) {
          matchedPorts.add(entry.getValue());
          if (!allowMultiple) {
            break;
          }
        }
      }
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (mode == null || (!MODE_SELECT_KEY.equals(mode) && !MODE_EXPRESSION.equals(mode))) {
      return Mono.error(
          new IllegalArgumentException(
              "Invalid or missing mode. Must be SELECT_KEY or EXPRESSION"));
    }
    if (MODE_SELECT_KEY.equals(mode) && (config.get(CONFIG_SELECTOR) == null)) {
      return Mono.error(new IllegalArgumentException("selector is mandatory for SELECT_KEY mode"));
    }
    final Map<String, String> cases = (Map<String, String>) config.get(CONFIG_CASES);
    if (cases == null || cases.isEmpty()) {
      return Mono.error(new IllegalArgumentException("cases map is mandatory and cannot be empty"));
    }
    return Mono.empty();
  }
}

 ```

---
### LoopPredicateProcessor.java
Location: `java/com/infenia/yukta/plugin/core/LoopPredicateProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.WorkflowPlugin;
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

 ```

---
### JoinProcessor.java
Location: `java/com/infenia/yukta/plugin/core/JoinProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.JoinTimeoutException;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.service.join.JoinStore;
import com.infenia.yukta.service.join.JoinStore.JoinConfig;
import com.infenia.yukta.service.join.JoinStore.JoinResult;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Synchronizes multiple incoming execution paths by waiting for criteria to be met. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods", "PMD.GodClass", "PMD.LawOfDemeter"})
public class JoinProcessor implements ProcessorPlugin {

  private static final String TYPE = "JOIN";
  private static final String MODE_ALL = "ALL";
  private static final String MODE_ANY = "ANY";
  private static final String MODE_CUSTOM = "CUSTOM_COUNT";

  private static final String STRAT_ARRAY = "ARRAY";
  private static final String STRAT_MERGE = "OBJECT_MERGE";
  private static final String STRAT_LATEST = "LATEST";

  private static final String CFG_MODE = "mode";
  private static final String CFG_ANCESTORS = "expectedAncestors";
  private static final String CFG_TIMEOUT = "timeoutMs";
  private static final String CFG_MERGE = "mergeStrategy";
  private static final String CFG_STRICT = "strictMode";
  private static final String CFG_COUNT = "count";
  private static final String CFG_CORR_KEY = "correlationKey";
  private static final String CFG_MAX_PEND = "maxPendingJoins";
  private static final String CFG_ERR_PORT = "errorPort";
  private static final String CFG_LATE_PORT = "latePort";

  private static final long DEFAULT_TIMEOUT = 30_000L;
  private static final int DEFAULT_MAX_PEND = 10_000;

  @Autowired private JoinStore joinStore;

  /** Default constructor. */
  public JoinProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Synchronizes multiple incoming execution paths by waiting for criteria to be met.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'ALL' (waits for all ancestors), 'ANY' (first one wins), or 'CUSTOM_COUNT' "
        + "(waits for N messages).\n"
        + "- expectedAncestors: List of node IDs to wait for (required for 'ALL' mode).\n"
        + "- count: Number of messages to wait for (required for 'CUSTOM_COUNT' mode).\n"
        + "- correlationKey: SpEL expression to group messages. Defaults to traceId.\n"
        + "- mergeStrategy: 'ARRAY', 'OBJECT_MERGE', or 'LATEST'. Default is 'ARRAY'.\n"
        + "- timeoutMs: Maximum time to wait for join completion. Default is 30,000ms.\n"
        + "- strictMode: Boolean. If true (default), throws JoinTimeoutException on timeout if "
        + "errorPort is not set.\n"
        + "- errorPort: Optional port name to route messages when join fails or times out.\n"
        + "- latePort: Optional port name for messages arriving after join has already completed.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Duration getDefaultTimeout() {
    return Duration.ofSeconds(60);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.get(CFG_MODE);
    final Mono<Void> modeValid = validateMode(mode);
    if (modeValid != null) {
      return modeValid;
    }
    if (MODE_ALL.equals(mode)) {
      return validateAllMode(config);
    }
    if (MODE_CUSTOM.equals(mode)) {
      return validateCustomMode(config);
    }
    return Mono.empty();
  }

  private Mono<Void> validateMode(final String mode) {
    if (mode == null
        || (!MODE_ALL.equals(mode) && !MODE_ANY.equals(mode) && !MODE_CUSTOM.equals(mode))) {
      return Mono.error(new IllegalArgumentException("Valid mode is mandatory"));
    }
    return null;
  }

  private Mono<Void> validateAllMode(final Map<String, Object> config) {
    final List<?> ancestors = (List<?>) config.get(CFG_ANCESTORS);
    if (ancestors == null || ancestors.isEmpty()) {
      return Mono.error(new IllegalArgumentException("expectedAncestors mandatory for ALL"));
    }
    return Mono.empty();
  }

  private Mono<Void> validateCustomMode(final Map<String, Object> config) {
    if (config.get(CFG_COUNT) == null) {
      return Mono.error(new IllegalArgumentException("count mandatory for CUSTOM_COUNT"));
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final JoinConfig joinConfig = createJoinConfig(config);
    final String correlationKey = (String) config.get(CFG_CORR_KEY);
    final boolean strictMode = (Boolean) config.getOrDefault(CFG_STRICT, true);

    return input
        .groupBy(message -> getCorrelationId(message, correlationKey))
        .flatMap(group -> processGroup(group, joinConfig, config, strictMode));
  }

  private Flux<Message<?>> processGroup(
      final Flux<Message<?>> group,
      final JoinConfig joinConfig,
      final Map<String, Object> config,
      final boolean strictMode) {
    final Object corrId = ((reactor.core.publisher.GroupedFlux<?, Message<?>>) group).key();
    final String errorPort = (String) config.get(CFG_ERR_PORT);
    final String latePort = (String) config.get(CFG_LATE_PORT);

    return Flux.deferContextual(
        ctx -> {
          final String sessionId = ctx.getOrDefault("sessionId", "unknown");
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          final String key = sessionId + ":" + nodeId + ":" + corrId;

          final Flux<Message<?>> processed =
              group.flatMap(
                  message ->
                      joinStore
                          .addMessage(key, message.getSourceNodeId(), message, joinConfig)
                          .flatMapMany(
                              result ->
                                  handleJoinResult(
                                      result, message, config, errorPort, latePort, strictMode)));

          return processed.timeout(
              Mono.delay(Duration.ofMillis(joinConfig.timeoutMs())),
              v -> Mono.never(),
              Flux.defer(() -> handleTimeout(key, corrId, errorPort, strictMode)));
        });
  }

  private Flux<Message<?>> handleTimeout(
      final String key, final Object corrId, final String port, final boolean strict) {
    if (log.isWarnEnabled()) {
      log.warn("Join timeout for key: {}", key);
    }
    if (port != null) {
      return Flux.just(createTimeoutErrorMessage(corrId, port));
    }
    if (strict) {
      return Flux.error(new JoinTimeoutException("Join timed out for key: " + key));
    }
    return Flux.empty();
  }

  private JoinConfig createJoinConfig(final Map<String, Object> config) {
    @SuppressWarnings("unchecked")
    final List<String> ancestors = (List<String>) config.get(CFG_ANCESTORS);
    final long timeout = ((Number) config.getOrDefault(CFG_TIMEOUT, DEFAULT_TIMEOUT)).longValue();
    final int count = ((Number) config.getOrDefault(CFG_COUNT, 0)).intValue();
    final int maxPending =
        ((Number) config.getOrDefault(CFG_MAX_PEND, DEFAULT_MAX_PEND)).intValue();

    return new JoinConfig(
        (String) config.get(CFG_MODE),
        ancestors != null ? ancestors : List.of(),
        timeout,
        count,
        maxPending);
  }

  private Object getCorrelationId(final Message<?> message, final String expression) {
    if (expression == null || expression.isBlank()) {
      return message.getTraceId();
    }
    return SpelUtils.evaluateSync(expression, message);
  }

  private Flux<Message<?>> handleJoinResult(
      final JoinResult result,
      final Message<?> current,
      final Map<String, Object> config,
      final String errPort,
      final String latePort,
      final boolean strict) {
    return switch (result.status()) {
      case COMPLETED -> Flux.just(createMergedMessage(result.collectedMessages(), current, config));
      case LATE_ARRIVAL ->
          latePort != null ? Flux.just(current.withSourcePort(latePort)) : Flux.empty();
      case OVERFLOW -> handleOverflow(current, errPort, strict);
      case WAITING -> Flux.empty();
    };
  }

  private Flux<Message<?>> handleOverflow(
      final Message<?> message, final String port, final boolean strict) {
    if (port != null) {
      return Flux.just(createErrorMessage(message, "JoinStore overflow", port));
    }
    if (strict) {
      return Flux.error(new RuntimeException("JoinStore overflow"));
    }
    return Flux.empty();
  }

  private Message<?> createMergedMessage(
      final Map<String, Message<?>> messages,
      final Message<?> last,
      final Map<String, Object> config) {
    final String strategy = (String) config.getOrDefault(CFG_MERGE, STRAT_ARRAY);
    @SuppressWarnings("unchecked")
    final List<String> ancestors = (List<String>) config.get(CFG_ANCESTORS);

    final Object payload =
        switch (strategy) {
          case STRAT_ARRAY -> mergeAsArray(ancestors, messages);
          case STRAT_MERGE -> MergeUtils.mergeObjects(ancestors, messages);
          case STRAT_LATEST -> last.getPayload();
          default -> last.getPayload();
        };
    return DefaultMessage.from(last, payload);
  }

  private List<Object> mergeAsArray(
      final List<String> ancestors, final Map<String, Message<?>> messages) {
    if (ancestors == null || ancestors.isEmpty()) {
      return messages.values().stream().map(msg -> (Object) msg.getPayload()).toList();
    }
    return ancestors.stream()
        .map(messages::get)
        .filter(java.util.Objects::nonNull)
        .map(msg -> (Object) msg.getPayload())
        .toList();
  }

  private Message<?> createErrorMessage(
      final Message<?> original, final String error, final String port) {
    return original.withSourcePort(port).withFailure(null, error, null);
  }

  private Message<?> createTimeoutErrorMessage(final Object corrId, final String port) {
    final UUID traceId = (corrId instanceof UUID) ? (UUID) corrId : UUID.randomUUID();
    return DefaultMessage.create(traceId, "Timeout")
        .withSourcePort(port)
        .withFailure(null, "Join timed out", null)
        .withControl(true);
  }
}

 ```

---
### LoopStreamProcessor.java
Location: `java/com/infenia/yukta/plugin/core/LoopStreamProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.WorkflowPlugin;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowRegistry;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  @Autowired private ObjectProvider<WorkflowRegistry> registryProvider;

  @Autowired private ObjectProvider<TaskTrackerService> trackerProvider;

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
                    executionId, nodeId, "Max iterations (" + context.maxIterations() + ")", state);
              }
              if (System.currentTimeMillis() - startTime > context.maxDuration().toMillis()) {
                return logAndTerminate(
                    executionId, nodeId, "Max duration (" + context.maxDuration() + ")", state);
              }

              return logIteration(executionId, nodeId, state.iteration() + 1)
                  .thenMany(
                      context
                          .processor()
                          .process(Flux.just(state.lastMessage()), context.targetConfig()))
                  .collectList()
                  .flatMap(
                      messages -> {
                        final Message lastMsg =
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
                                            messages, lastMsg, state.iteration() + 1, true));
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

  private record LoopState(
      List<Message<?>> messages, Message<?> lastMessage, int iteration, boolean terminated) {
    /* default */ LoopState withTerminated(final boolean term) {
      return new LoopState(messages, lastMessage, iteration, term);
    }
  }
}

 ```

---
### MergeUtils.java
Location: `java/com/infenia/yukta/plugin/core/MergeUtils.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Utility for merging messages and payloads. */
@SuppressWarnings("PMD.LawOfDemeter")
public final class MergeUtils {

  private MergeUtils() {
    // Utility class
  }

  /**
   * Merge objects from multiple messages based on the given order.
   *
   * @param ancestors the expected order of ancestors
   * @param messages the map of collected messages
   * @return the merged object
   */
  public static Object mergeObjects(
      final List<String> ancestors, final Map<String, Message<?>> messages) {
    final Map<String, Object> result = new ConcurrentHashMap<>();
    final List<String> order =
        (ancestors != null && !ancestors.isEmpty())
            ? ancestors
            : new ArrayList<>(messages.keySet());
    for (final String sourceId : order) {
      final Message<?> msg = messages.get(sourceId);
      if (msg != null && msg.getPayload() instanceof Map) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> payloadMap = (Map<String, Object>) msg.getPayload();
        deepMerge(result, payloadMap);
      }
    }
    return result;
  }

  /**
   * Deep merge two maps.
   *
   * @param target the target map
   * @param source the source map
   */
  @SuppressWarnings({"unchecked", "PMD.AvoidInstantiatingObjectsInLoops"})
  public static void deepMerge(final Map<String, Object> target, final Map<String, Object> source) {
    for (final Map.Entry<String, Object> entry : source.entrySet()) {
      final String key = entry.getKey();
      final Object sVal = entry.getValue();
      final Object tVal = target.get(key);
      if (sVal instanceof Map && tVal instanceof Map) {
        // Defensive copy to handle immutable maps
        final Map<String, Object> targetMap = (Map<String, Object>) tVal;
        final Map<String, Object> mutableTargetMap;
        try {
          mutableTargetMap = targetMap;
          mutableTargetMap.putAll(Map.of()); // Test mutability
        } catch (final UnsupportedOperationException e) {
          final Map<String, Object> newTargetMap = new ConcurrentHashMap<>(targetMap);
          target.put(key, newTargetMap);
          deepMerge(newTargetMap, (Map<String, Object>) sVal);
          continue;
        }
        deepMerge(mutableTargetMap, (Map<String, Object>) sVal);
      } else {
        target.put(key, sVal);
      }
    }
  }
}

 ```

---
### ApiTriggerPlugin.java
Location: `java/com/infenia/yukta/plugin/core/ApiTriggerPlugin.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.TriggerPlugin;
import com.infenia.yukta.plugin.UiDesign;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A TriggerPlugin that emits the payload received from an API trigger. */
@Slf4j
@Component
public class ApiTriggerPlugin implements TriggerPlugin {

  /** Default constructor. */
  public ApiTriggerPlugin() {
    super();
  }

  @Override
  public String getDescription() {
    return "Emits the payload received from an API trigger.";
  }

  @Override
  public String getUsagePattern() {
    return "This plugin is automatically used when triggering a workflow via the REST API. "
        + "It passes the 'payload' map from the trigger request to the workflow.";
  }

  @Override
  public String getType() {
    return "api-trigger";
  }

  @Override
  public List<String> getOutputPorts() {
    return List.of("default");
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1 relative">
                <span class="material-symbols-outlined text-blue-500 text-2xl">api</span>
                <div class="text-[10px] text-blue-600 font-bold uppercase tracking-widest">Trigger</div>
                <div class="yukta-port absolute -right-3 top-1/2 -translate-y-1/2 w-4 h-4 bg-blue-600 rounded-full border-2 border-white shadow-sm flex items-center justify-center" data-port-name="default">
                    <div class="w-1.5 h-1.5 bg-white rounded-full"></div>
                </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Map<String, Object> payload = ctx.get("payload");
          return Flux.just(DefaultMessage.create(UUID.randomUUID(), payload));
        });
  }
}

 ```

---
### SubWorkflowProcessor.java
Location: `java/com/infenia/yukta/plugin/core/SubWorkflowProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.WorkflowGateway;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

 ```

---
### ConsoleTerminalPlugin.java
Location: `java/com/infenia/yukta/plugin/core/ConsoleTerminalPlugin.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.TerminalPlugin;
import com.infenia.yukta.plugin.UiDesign;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A simple TerminalPlugin that logs message payloads to the console. */
@Slf4j
@Component
public class ConsoleTerminalPlugin implements TerminalPlugin {

  /** Default constructor. */
  public ConsoleTerminalPlugin() {
    super();
  }

  @Override
  public String getDescription() {
    return "Logs message payloads to the console/logger.";
  }

  @Override
  public String getUsagePattern() {
    return "Consumes messages and prints their payload to the application logs. No configuration"
        + " required.";
  }

  @Override
  public String getType() {
    return "console";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1">
                <span class="material-symbols-outlined text-slate-400 text-2xl">terminal</span>
                <div class="text-[10px] text-slate-500 font-mono">Logger</div>
            </div>
            """,
            120,
            80));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.doOnNext(msg -> log.info("Consuming message: {}", msg.getPayload())).then();
  }
}

 ```

---
### MapMessageMapper.java
Location: `java/com/infenia/yukta/plugin/core/MapMessageMapper.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.MessageMapper;
import java.util.Map;

/** Example MessageMapper that maps between a generic Message and a Map domain object. */
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class MapMessageMapper implements MessageMapper<Object, Map<String, Object>> {

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> toDomain(final Message<Object> message) {
    if (message.getPayload() instanceof Map) {
      return (Map<String, Object>) message.getPayload();
    }
    return Map.of("value", message.getPayload());
  }

  @Override
  public Message<Object> fromDomain(final Map<String, Object> domain, final Message<?> original) {
    return original.withPayload(domain);
  }
}

 ```

---
### ConstantProcessor.java
Location: `java/com/infenia/yukta/plugin/core/ConstantProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Constant processor enriches or replaces message payload/metadata with predefined variables. */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidDeeplyNestedIfStmts", "PMD.UseConcurrentHashMap"})
public class ConstantProcessor implements ProcessorPlugin {

  private static final String TYPE = "CONSTANT";
  private static final String MODE_ENRICH = "ENRICH";
  private static final String MODE_REPLACE = "REPLACE";
  private static final String TARGET_METADATA = "METADATA";
  private static final String TARGET_PAYLOAD = "PAYLOAD";
  private static final String POLICY_OVERWRITE = "OVERWRITE";
  private static final String POLICY_SKIP = "SKIP";
  private static final String POLICY_FAIL = "FAIL";

  private final VariableResolver resolver;
  private final Map<Object, Object> staticValueCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Enriches or replaces message payload or metadata with predefined constant variables.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- variables: Map of key-value pairs to inject. Supports SpEL expressions.\n"
        + "- mode: 'ENRICH' (adds to existing) or 'REPLACE' (replaces entire target). "
        + "Default is 'ENRICH'.\n"
        + "- target: 'PAYLOAD' or 'METADATA'. Default is 'PAYLOAD'.\n"
        + "- collisionPolicy: 'OVERWRITE', 'SKIP', or 'FAIL'. Default is 'OVERWRITE'.";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get("variables") == null) {
      return Mono.error(new IllegalArgumentException("variables is mandatory"));
    }
    return Mono.empty();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Void> prepare(final Map<String, Object> config) {
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    return Flux.fromIterable(variables.values())
        .filter(resolver::isStatic)
        .flatMap(
            val -> resolver.resolve(val).doOnNext(resolved -> staticValueCache.put(val, resolved)))
        .then();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault("mode", MODE_ENRICH);
    final String target = (String) config.getOrDefault("target", TARGET_PAYLOAD);
    final String collisionPolicy =
        (String) config.getOrDefault("collisionPolicy", POLICY_OVERWRITE);
    @SuppressWarnings("unchecked")
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());

    return input.flatMap(
        message ->
            resolveVariables(variables)
                .map(
                    resolvedVars -> {
                      final Map<String, Object> metadata = new HashMap<>(message.getMetadata());
                      Object payload = message.getPayload();

                      if (TARGET_METADATA.equalsIgnoreCase(target)) {
                        applyVariables(metadata, resolvedVars, mode, collisionPolicy);
                        return message.withMetadata(metadata);
                      } else {
                        final Map<String, Object> payloadMap =
                            MODE_REPLACE.equalsIgnoreCase(mode)
                                ? new HashMap<>()
                                : MapUtils.asMutableMap(payload);
                        applyVariables(payloadMap, resolvedVars, mode, collisionPolicy);
                        payload = payloadMap;
                        return message.withPayload(payload);
                      }
                    }));
  }

  private Mono<Map<String, Object>> resolveVariables(final Map<String, Object> variables) {
    return Flux.fromIterable(variables.entrySet())
        .flatMap(
            entry -> {
              final Object val = entry.getValue();
              if (resolver.isStatic(val)) {
                final Object cached = staticValueCache.getOrDefault(val, val);
                return Mono.just(Map.entry(entry.getKey(), cached));
              }
              return resolver.resolve(val).map(resolved -> Map.entry(entry.getKey(), resolved));
            })
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  private void applyVariables(
      final Map<String, Object> targetMap,
      final Map<String, Object> variables,
      final String mode,
      final String policy) {
    variables.forEach(
        (path, value) -> {
          if (MODE_ENRICH.equalsIgnoreCase(mode)) {
            final Object existing = MapUtils.getNestedValue(targetMap, path);
            if (existing != null) {
              if (POLICY_SKIP.equalsIgnoreCase(policy)) {
                return;
              }
              if (POLICY_FAIL.equalsIgnoreCase(policy)) {
                throw new IllegalStateException("Collision detected for key: " + path);
              }
            }
          }
          MapUtils.setNestedValue(targetMap, path, value);
        });
  }
}

 ```

---
### FilterProcessor.java
Location: `java/com/infenia/yukta/plugin/core/FilterProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.FilterEvaluationException;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.PluginMetricsReporter;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluates a boolean predicate against a message. If true, the message passes through; if false,
 * it is dropped or rerouted.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
public final class FilterProcessor implements ProcessorPlugin {

  private static final String TYPE = "FILTER";
  private static final String ENGINE_SPEL = "SpEL";
  private static final String ENGINE_SIMPLE = "SIMPLE";
  private static final String ENGINE_REGO = "REGO";

  private static final String CONFIG_CONDITION = "condition";
  private static final String CONFIG_ENGINE = "engine";
  private static final String CONFIG_STRICT = "strictMode";
  private static final String DISCARD_PORT = "discardPort";

  private final PluginMetricsReporter reporter;

  /**
   * Constructs a new FilterProcessor with an optional metrics reporter.
   *
   * @param reporterProvider the provider for PluginMetricsReporter
   */
  public FilterProcessor(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.reporter = reporterProvider.getIfAvailable(() -> (nodeId, status) -> {});
  }

  @Override
  public String getDescription() {
    return "Evaluates a boolean predicate against a message. If true, the message passes through; "
        + "if false, it is dropped or rerouted.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- condition: The boolean expression to evaluate.\n"
        + "- engine: 'SpEL' (default) or 'SIMPLE'.\n"
        + "- strictMode: Boolean. If true (default), throws exception on evaluation error.\n"
        + "- discardPort: Optional port name to route messages that do not match the condition.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final String engine = (String) config.getOrDefault(CONFIG_ENGINE, ENGINE_SPEL);

    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory for Filter plugin"));
    }

    if (ENGINE_SPEL.equalsIgnoreCase(engine)) {
      SpelUtils.preParse(condition);
    } else if (ENGINE_SIMPLE.equalsIgnoreCase(engine)) {
      SimpleExpressionEvaluator.preParse(condition);
    }

    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final String engine = (String) config.getOrDefault(CONFIG_ENGINE, ENGINE_SPEL);
    final boolean strictMode = (Boolean) config.getOrDefault(CONFIG_STRICT, true);
    final String discardPort = (String) config.get(DISCARD_PORT);

    return input.flatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  return executeFilter(message, nodeId, condition, engine, strictMode, discardPort);
                }));
  }

  private Mono<Message<?>> executeFilter(
      final Message<?> message,
      final String nodeId,
      final String condition,
      final String engine,
      final boolean strictMode,
      final String discardPort) {
    try {
      final boolean isMatch = evaluate(condition, engine, message);
      return handleMatchResult(message, nodeId, isMatch, discardPort);
    } catch (final Exception e) {
      return handleEvaluationError(nodeId, condition, strictMode, e);
    }
  }

  private Mono<Message<?>> handleMatchResult(
      final Message<?> message,
      final String nodeId,
      final boolean isMatch,
      final String discardPort) {
    Mono<Message<?>> result = Mono.empty();
    if (isMatch) {
      reporter.incrementFilterCount(nodeId, "MATCH");
      result = Mono.just(message);
    } else {
      reporter.incrementFilterCount(nodeId, "DISCARD");
      if (discardPort != null && !discardPort.isBlank()) {
        result = Mono.just(message.withSourcePort(discardPort));
      }
    }
    return result;
  }

  private Mono<Message<?>> handleEvaluationError(
      final String nodeId, final String condition, final boolean strictMode, final Exception err) {
    reporter.incrementFilterCount(nodeId, "ERROR");
    if (log.isErrorEnabled()) {
      log.error("Filter evaluation failed for condition [{}]: {}", condition, err.getMessage());
    }
    if (!strictMode) {
      return Mono.empty();
    }
    throw new FilterEvaluationException(
        "Filter evaluation failed for condition: " + condition, err);
  }

  private boolean evaluate(final String condition, final String engine, final Message<?> message) {
    if (ENGINE_SPEL.equalsIgnoreCase(engine)) {
      final Boolean result = SpelUtils.evaluateSync(condition, message);
      return result != null && result;
    } else if (ENGINE_SIMPLE.equalsIgnoreCase(engine)) {
      return SimpleExpressionEvaluator.evaluate(condition, message);
    }
    throw new IllegalArgumentException("Unsupported engine: " + engine);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory"));
    }
    final String engine = (String) config.getOrDefault(CONFIG_ENGINE, ENGINE_SPEL);
    if (!ENGINE_SPEL.equalsIgnoreCase(engine)
        && !ENGINE_SIMPLE.equalsIgnoreCase(engine)
        && !ENGINE_REGO.equalsIgnoreCase(engine)) {
      return Mono.error(new IllegalArgumentException("Unsupported engine: " + engine));
    }
    if (ENGINE_REGO.equalsIgnoreCase(engine)) {
      return Mono.error(
          new IllegalArgumentException(
              "REGO engine is reserved for future use and not yet implemented."));
    }
    return Mono.empty();
  }
}

 ```

---
### ConstantSource.java
Location: `java/com/infenia/yukta/plugin/core/ConstantSource.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.TriggerPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Constant source plugin emits a message with predefined variables at workflow startup. */
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
public class ConstantSource implements TriggerPlugin {

  private static final String TYPE = "CONSTANT_SOURCE";
  private static final String TARGET_METADATA = "METADATA";
  private static final String TARGET_PAYLOAD = "PAYLOAD";

  private final VariableResolver resolver;
  private final Map<Object, Object> staticValueCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Emits a message with predefined variables at workflow startup.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- variables: Map of key-value pairs to emit. Supports SpEL expressions.\n"
        + "- target: 'PAYLOAD' or 'METADATA'. Default is 'PAYLOAD'.";
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Void> prepare(final Map<String, Object> config) {
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    return Flux.fromIterable(variables.values())
        .filter(resolver::isStatic)
        .flatMap(
            val -> resolver.resolve(val).doOnNext(resolved -> staticValueCache.put(val, resolved)))
        .then();
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    @SuppressWarnings("unchecked")
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    final String target = (String) config.getOrDefault("target", TARGET_PAYLOAD);

    return resolveVariables(variables)
        .<Message<?>>map(
            resolvedVars -> {
              final Map<String, Object> metadata = new HashMap<>();
              Object resultPayload = new HashMap<>();

              if (TARGET_METADATA.equalsIgnoreCase(target)) {
                resolvedVars.forEach((k, v) -> MapUtils.setNestedValue(metadata, k, v));
              } else {
                final Map<String, Object> payloadMap = new HashMap<>();
                resolvedVars.forEach((k, v) -> MapUtils.setNestedValue(payloadMap, k, v));
                resultPayload = payloadMap;
              }

              return DefaultMessage.create(UUID.randomUUID(), resultPayload).withMetadata(metadata);
            })
        .flux();
  }

  private Mono<Map<String, Object>> resolveVariables(final Map<String, Object> variables) {
    return Flux.fromIterable(variables.entrySet())
        .flatMap(
            entry -> {
              final Object val = entry.getValue();
              if (resolver.isStatic(val)) {
                final Object cached = staticValueCache.getOrDefault(val, val);
                return Mono.just(Map.entry(entry.getKey(), cached));
              }
              return resolver.resolve(val).map(resolved -> Map.entry(entry.getKey(), resolved));
            })
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get("variables") == null) {
      return Mono.error(new IllegalArgumentException("variables is mandatory"));
    }
    return Mono.empty();
  }
}

 ```

---
### AggregatorProcessor.java
Location: `java/com/infenia/yukta/plugin/core/AggregatorProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.service.aggregate.AggregateStore;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateConfig;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateResult;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Aggregates multiple incoming messages into a single window based on count, time, or session. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn"})
public class AggregatorProcessor implements ProcessorPlugin {

  private static final String TYPE = "AGGREGATOR";

  private static final String CFG_GROUP_BY = "groupBy";
  private static final String CFG_WINDOW = "window";
  private static final String CFG_AGGREGATION = "aggregation";
  private static final String CFG_MAX_PENDING = "maxPendingWindows";
  private static final String CFG_EMIT_TIMEOUT = "emitOnTimeout";
  private static final String CFG_NULL_POLICY = "nullPolicy";

  private static final String WIN_TYPE = "type";
  private static final String WIN_SIZE = "size";
  private static final String WIN_DURATION = "durationMs";

  private static final String AGG_TYPE = "type";
  private static final String AGG_FIELD = "field";
  private static final String AGG_INIT = "initValue";
  private static final String AGG_ACC = "accumulateExp";
  private static final String AGG_RES = "resultExp";

  private static final String UNCHECKED = "unchecked";

  private static final int DEF_MAX_PEND = 1000;

  @Autowired private AggregateStore aggregateStore;

  /** Default constructor. */
  public AggregatorProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Aggregates multiple incoming messages into a single window based on count, time, or"
        + " session triggers.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- groupBy: SpEL expression to group messages into windows.\n"
        + "- window: Map containing 'type' (COUNT, TIME, SESSION) and 'size' or 'durationMs'.\n"
        + "- aggregation: Map containing 'type' (SUM, AVERAGE, MIN, MAX, COLLECT_LIST, CUSTOM) "
        + "and optional 'field' or 'accumulateExp'/'resultExp' for CUSTOM.\n"
        + "- maxPendingWindows: Maximum number of windows to keep in memory.\n"
        + "- emitOnTimeout: Boolean. If true (default), partial windows are emitted on timeout.\n"
        + "- nullPolicy: 'IGNORE' (default) or 'FAIL'.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Duration getDefaultTimeout() {
    return Duration.ofSeconds(60);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get(CFG_GROUP_BY) == null) {
      return Mono.error(new IllegalArgumentException("groupBy is mandatory"));
    }
    if (!(config.get(CFG_WINDOW) instanceof Map<?, ?> window)) {
      return Mono.error(new IllegalArgumentException("window configuration is mandatory"));
    }
    if (window.get(WIN_TYPE) == null) {
      return Mono.error(new IllegalArgumentException("window type is mandatory"));
    }
    if (!(config.get(CFG_AGGREGATION) instanceof Map<?, ?> aggregation)) {
      return Mono.error(new IllegalArgumentException("aggregation configuration is mandatory"));
    }
    if (aggregation.get(AGG_TYPE) == null) {
      return Mono.error(new IllegalArgumentException("aggregation type is mandatory"));
    }
    return Mono.empty();
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    SpelUtils.preParse((String) config.get(CFG_GROUP_BY));
    @SuppressWarnings(UNCHECKED)
    final Map<String, Object> aggregation = (Map<String, Object>) config.get(CFG_AGGREGATION);
    SpelUtils.preParse((String) aggregation.get(AGG_FIELD));
    SpelUtils.preParse((String) aggregation.get(AGG_ACC));
    SpelUtils.preParse((String) aggregation.get(AGG_RES));
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final AggregateConfig aggConfig = createAggregateConfig(config);
    final String groupBy = (String) config.get(CFG_GROUP_BY);
    @SuppressWarnings(UNCHECKED)
    final Map<String, Object> aggMap = (Map<String, Object>) config.get(CFG_AGGREGATION);
    final String aggField = (String) aggMap.get(AGG_FIELD);

    return Flux.deferContextual(
        ctx -> {
          final String sessionId = ctx.getOrDefault("sessionId", "unknown");
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          final String keyPrefix = sessionId + ":" + nodeId + ":";

          final Flux<Message<?>> incoming =
              input.flatMap(
                  msg -> {
                    final Object keyVal = SpelUtils.evaluateSync(groupBy, msg);
                    final String key = keyPrefix + keyVal;
                    final Object val =
                        aggField != null ? SpelUtils.evaluateSync(aggField, msg) : msg.getPayload();

                    return aggregateStore
                        .addValue(key, val, msg, aggConfig)
                        .flatMapMany(this::handleResult);
                  });

          final Flux<Message<?>> async = getAsyncMessages(keyPrefix, input.then());

          final Flux<Message<?>> remaining =
              input.thenMany(
                  Flux.defer(() -> aggregateStore.flushAll(keyPrefix).map(this::createMessage)));

          return Flux.merge(incoming, async).concatWith(remaining);
        });
  }

  @SuppressWarnings("PMD.LawOfDemeter")
  private Flux<Message<?>> getAsyncMessages(final String keyPrefix, final Mono<Void> completion) {
    return aggregateStore
        .getAsyncResults()
        .filter(res -> res.key().startsWith(keyPrefix))
        .takeUntilOther(completion)
        .map(this::createMessage);
  }

  @Override
  public Mono<Void> shutdown(final Map<String, Object> config) {
    return aggregateStore.flushAll().map(this::createMessage).then();
  }

  private Flux<Message<?>> handleResult(final AggregateResult result) {
    if (result.status() == AggregateResult.Status.WAITING) {
      return Flux.empty();
    }
    return Flux.just(createMessage(result));
  }

  private Message<?> createMessage(final AggregateResult result) {
    return com.infenia.yukta.plugin.DefaultMessage.from(result.lastMessage(), result.result());
  }

  private AggregateConfig createAggregateConfig(final Map<String, Object> config) {
    @SuppressWarnings(UNCHECKED)
    final Map<String, Object> window = (Map<String, Object>) config.get(CFG_WINDOW);
    @SuppressWarnings(UNCHECKED)
    final Map<String, Object> aggregation = (Map<String, Object>) config.get(CFG_AGGREGATION);

    return new AggregateConfig(
        (String) window.get(WIN_TYPE),
        ((Number) window.getOrDefault(WIN_SIZE, 0)).intValue(),
        ((Number) window.getOrDefault(WIN_DURATION, 0L)).longValue(),
        (String) aggregation.get(AGG_TYPE),
        ((Number) config.getOrDefault(CFG_MAX_PENDING, DEF_MAX_PEND)).intValue(),
        (Boolean) config.getOrDefault(CFG_EMIT_TIMEOUT, true),
        (String) config.getOrDefault(CFG_NULL_POLICY, "IGNORE"),
        aggregation.get(AGG_INIT),
        (String) aggregation.get(AGG_ACC),
        (String) aggregation.get(AGG_RES));
  }
}

 ```

---
### SimpleExpressionEvaluator.java
Location: `java/com/infenia/yukta/plugin/core/SimpleExpressionEvaluator.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-speed, non-reflective evaluator for simple expressions. Supports: ==, exists, matches. */
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public final class SimpleExpressionEvaluator {
  private static final String PAYLOAD_PREFIX = "payload.";
  private static final String METADATA_PREFIX = "metadata.";
  private static final String PAYLOAD = "payload";

  private static final Pattern EXPR_PATTERN =
      Pattern.compile("^(\\S+)\\s+(==|exists|matches)(?:\\s+(.+))?$");
  private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();

  private SimpleExpressionEvaluator() {
    // Utility class
  }

  /**
   * Evaluate a simple expression against a message.
   *
   * @param expressionStr the expression string
   * @param message the message to evaluate
   * @return the result of evaluation
   * @throws IllegalArgumentException if the expression is invalid
   */
  public static boolean evaluate(final String expressionStr, final Message<?> message) {
    final Expression expression =
        CACHE.computeIfAbsent(expressionStr, SimpleExpressionEvaluator::parse);
    return expression.evaluate(message);
  }

  /**
   * Pre-parse and cache an expression.
   *
   * @param expressionStr the expression string
   */
  public static void preParse(final String expressionStr) {
    if (expressionStr != null && !expressionStr.isBlank()) {
      CACHE.computeIfAbsent(expressionStr, SimpleExpressionEvaluator::parse);
    }
  }

  private static Expression parse(final String expressionStr) {
    final Matcher matcher = EXPR_PATTERN.matcher(expressionStr.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid SIMPLE expression: " + expressionStr);
    }
    final String path = matcher.group(1);
    final String operator = matcher.group(2);
    final String operand = matcher.group(3);

    return switch (operator) {
      case "==" -> new EqualsExpression(path, stripQuotes(operand));
      case "exists" -> new ExistsExpression(path);
      case "matches" -> new MatchesExpression(path, stripQuotes(operand));
      default -> throw new UnsupportedOperationException("Operator " + operator + " not supported");
    };
  }

  private static String stripQuotes(final String str) {
    if (str == null) {
      return null;
    }
    String result = str.trim();
    if ((result.startsWith("'") && result.endsWith("'"))
        || (result.startsWith("\"") && result.endsWith("\""))) {
      result = result.substring(1, result.length() - 1);
    }
    return result;
  }

  private abstract static class Expression {
    protected final String path;

    protected Expression(final String path) {
      this.path = path;
    }

    /* default */

    abstract boolean evaluate(Message<?> message);

    protected Object getValue(final Message<?> message) {
      Object value = null;
      if (path.startsWith(PAYLOAD_PREFIX)) {
        value = getNested(message.getPayload(), path.substring(PAYLOAD_PREFIX.length()));
      } else if (path.startsWith(METADATA_PREFIX)) {
        value = message.getMetadata().get(path.substring(METADATA_PREFIX.length()));
      } else if (PAYLOAD.equals(path)) {
        value = message.getPayload();
      }
      return value;
    }

    private Object getNested(final Object obj, final String path) {
      Object result = null;
      if (obj instanceof Map<?, ?> map) {
        final int dotIndex = path.indexOf('.');
        if (dotIndex == -1) {
          result = map.get(path);
        } else {
          final String current = path.substring(0, dotIndex);
          final String remaining = path.substring(dotIndex + 1);
          final Object next = map.get(current);
          if (next != null) {
            result = getNested(next, remaining);
          }
        }
      }
      return result;
    }
  }

  private static class EqualsExpression extends Expression {
    private final String expected;

    /* default */ EqualsExpression(final String path, final String expected) {
      super(path);
      this.expected = expected;
    }

    @Override
    public boolean evaluate(final Message<?> message) {
      final Object value = getValue(message);
      return value == null ? expected == null : String.valueOf(value).equals(expected);
    }
  }

  private static class ExistsExpression extends Expression {
    /* default */ ExistsExpression(final String path) {
      super(path);
    }

    @Override
    public boolean evaluate(final Message<?> message) {
      return getValue(message) != null;
    }
  }

  private static class MatchesExpression extends Expression {
    private final Pattern pattern;

    /* default */ MatchesExpression(final String path, final String regex) {
      super(path);
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean evaluate(final Message<?> message) {
      final Object value = getValue(message);
      return value != null && pattern.matcher(String.valueOf(value)).matches();
    }
  }
}

 ```

---
### GuardProcessor.java
Location: `java/com/infenia/yukta/plugin/core/GuardProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluates a boolean condition against the input message and routes to 'true' or 'false' ports.
 * Supports SpEL expressions and high-performance non-blocking evaluation.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidThrowingRawExceptionTypes",
  "PMD.AvoidCatchingGenericException"
})
public class GuardProcessor implements ProcessorPlugin {

  private static final String TYPE = "GUARD";
  private static final String PORT_TRUE = "true";
  private static final String PORT_FALSE = "false";
  private static final String CONFIG_CONDITION = "condition";
  private static final String STRICT = "strictMode";
  private static final String ERROR_PORT = "errorPort";

  /** Default constructor. */
  public GuardProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Evaluates a boolean condition and routes the message to 'true' or 'false' ports based"
        + " on the result.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- condition: The SpEL expression to evaluate.\n"
        + "- strictMode: Boolean. If true (default), throws exception on evaluation error if "
        + "errorPort is not set.\n"
        + "- errorPort: Optional port name to route messages when evaluation fails. Adds "
        + "'error_message' to metadata.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory for Guard plugin"));
    }
    SpelUtils.preParse(condition);
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);
    final String errorPort = (String) config.get(ERROR_PORT);

    return input.map(
        message -> {
          try {
            final Boolean result = SpelUtils.evaluateSync(condition, message);
            final String port = (result != null && result) ? PORT_TRUE : PORT_FALSE;
            return message.withSourcePort(port);
          } catch (final Exception e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Guard evaluation failed for condition [{}]: {}", condition, e.getMessage());
            }
            if (errorPort != null) {
              return message
                  .withSourcePort(errorPort)
                  .withFailure(null, "Guard evaluation failed", e.getMessage());
            }
            if (!strictMode) {
              return message.withSourcePort(PORT_FALSE);
            }
            throw new RuntimeException(
                "WorkflowExecutionException: Guard evaluation failed for condition: " + condition,
                e);
          }
        });
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory"));
    }
    return Mono.empty();
  }
}

 ```

---
### MapperProcessor.java
Location: `java/com/infenia/yukta/plugin/core/MapperProcessor.java`

```
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
package com.infenia.yukta.plugin.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.SpelUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Mapper processor transforms message payloads using PROJECTION, TEMPLATE, or SCRIPT modes. */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidThrowingRawExceptionTypes",
  "PMD.TooManyMethods",
  "PMD.AvoidCatchingGenericException",
  "PMD.ExceptionAsFlowControl",
  "PMD.CyclomaticComplexity",
  "PMD.UseConcurrentHashMap",
  "PMD.GodClass",
  "PMD.LawOfDemeter"
})
public class MapperProcessor implements ProcessorPlugin {

  private static final String TYPE = "MAPPER";
  private static final String UNCHECKED = "unchecked";

  private static final String MODE_PROJECTION = "PROJECTION";
  private static final String MODE_TEMPLATE = "TEMPLATE";
  private static final String MODE_SCRIPT = "SCRIPT";

  private static final String CONFIG_MODE = "mode";
  private static final String CONFIG_MAPPING = "mapping";
  private static final String DROP_ORIG = "dropOriginal";
  private static final String STRICT = "strictMode";

  private static final String ERR_PREFIX = "WorkflowExecutionException: ";

  private static final Engine JS_ENGINE =
      Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();

  private final Handlebars handlebars = new Handlebars();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MapMessageMapper mapMapper = new MapMessageMapper();
  private final DefaultConversionService conversionService = new DefaultConversionService();

  private final Map<String, Template> templateCache = new ConcurrentHashMap<>();
  private final Map<String, Source> jsSourceCache = new ConcurrentHashMap<>();

  /** Default constructor. */
  public MapperProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Transforms message payloads using PROJECTION (SpEL), TEMPLATE (Handlebars), or SCRIPT"
        + " (GraalVM JS) modes.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'PROJECTION', 'TEMPLATE', or 'SCRIPT'.\n"
        + "- mapping:\n"
        + "    - For PROJECTION: Map of target field (supports dot notation) to SpEL expression.\n"
        + "    - For TEMPLATE: Map of target field to Handlebars template, or a single string "
        + "template.\n"
        + "    - For SCRIPT: A JavaScript string returning the new payload.\n"
        + "- dropOriginal: Boolean. If true (default), original payload is discarded.\n"
        + "- strictMode: Boolean. If true (default), throws exception on transformation errors.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final Object mapping = config.get(CONFIG_MAPPING);

    if (MODE_PROJECTION.equals(mode) && mapping instanceof Map) {
      ((Map<String, String>) mapping).values().forEach(SpelUtils::preParse);
    } else if (MODE_TEMPLATE.equals(mode)) {
      initializeTemplates(mapping);
    } else if (MODE_SCRIPT.equals(mode) && mapping instanceof String) {
      jsSourceCache.computeIfAbsent(
          (String) mapping, s -> Source.newBuilder("js", s, "mapper.js").buildLiteral());
    }
    return Mono.empty();
  }

  @SuppressWarnings(UNCHECKED)
  private void initializeTemplates(final Object mapping) {
    if (mapping instanceof Map) {
      ((Map<String, String>) mapping).values().forEach(this::compileTemplate);
    } else if (mapping instanceof String) {
      compileTemplate((String) mapping);
    }
  }

  private void compileTemplate(final String templateStr) {
    templateCache.computeIfAbsent(
        templateStr,
        t -> {
          try {
            return handlebars.compileInline(t);
          } catch (Exception e) {
            throw new RuntimeException("Failed to compile Handlebars template", e);
          }
        });
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final Object mapping = config.get(CONFIG_MAPPING);
    final boolean dropOriginal = (Boolean) config.getOrDefault(DROP_ORIG, true);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);

    return input.flatMap(
        message -> {
          try {
            final Object resultPayload =
                executeInternal(mode, mapping, message, dropOriginal, strictMode);

            return Flux.just(DefaultMessage.from(message, resultPayload));
          } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Mapping failed for message {}: {}", message.getMessageId(), e.getMessage());
            }
            return Flux.error(
                new RuntimeException(ERR_PREFIX + "Mapping failed: " + e.getMessage(), e));
          }
        });
  }

  @SuppressWarnings(UNCHECKED)
  private Object executeInternal(
      final String mode,
      final Object mapping,
      final Message<?> message,
      final boolean dropOriginal,
      final boolean strictMode) {
    return switch (mode) {
      case MODE_PROJECTION ->
          executeProjection(message, (Map<String, String>) mapping, dropOriginal, strictMode);
      case MODE_TEMPLATE -> executeTemplate(message, mapping, dropOriginal, strictMode);
      case MODE_SCRIPT -> executeScript(message, (String) mapping, dropOriginal, strictMode);
      default -> throw new IllegalArgumentException("Unsupported Mapper mode: " + mode);
    };
  }

  private Object executeProjection(
      final Message<?> message,
      final Map<String, String> mapping,
      final boolean dropOriginal,
      final boolean strictMode) {
    @SuppressWarnings("unchecked")
    final Message<Object> castMsg = (Message<Object>) message;
    final Map<String, Object> domain = mapMapper.toDomain(castMsg);

    final Map<String, Object> result =
        dropOriginal ? new HashMap<>() : MapUtils.asMutableMap(domain);
    for (final Map.Entry<String, String> entry : mapping.entrySet()) {
      try {
        final Object value = SpelUtils.evaluateSync(entry.getValue(), message);
        if (value != null || strictMode) {
          MapUtils.setNestedValue(result, entry.getKey(), value);
        }
      } catch (RuntimeException e) {
        if (strictMode) {
          throw e;
        }
        if (log.isWarnEnabled()) {
          log.warn("Projection failed for {}: {}", entry.getKey(), e.getMessage());
        }
      }
    }
    return result;
  }

  @SuppressWarnings(UNCHECKED)
  private Object executeTemplate(
      final Message<?> message,
      final Object mapping,
      final boolean dropOriginal,
      final boolean strictMode) {
    final Object result;
    if (mapping instanceof Map) {
      result = executeTemplateMap(message, (Map<String, String>) mapping, dropOriginal, strictMode);
    } else if (mapping instanceof String) {
      result = executeTemplateString(message, (String) mapping, strictMode);
    } else {
      throw new IllegalArgumentException("Invalid mapping for TEMPLATE mode");
    }
    return result;
  }

  private Map<String, Object> executeTemplateMap(
      final Message<?> message,
      final Map<String, String> mapping,
      final boolean dropOriginal,
      final boolean strictMode) {
    final Map<String, Object> mapResult =
        dropOriginal ? new HashMap<>() : MapUtils.asMutableMap(message.getPayload());
    for (final Map.Entry<String, String> entry : mapping.entrySet()) {
      try {
        final Template template = templateCache.get(entry.getValue());
        if (template == null) {
          throw new IllegalArgumentException(
              "Template not found in cache for key: " + entry.getValue());
        }
        final String value = template.apply(message);
        MapUtils.setNestedValue(mapResult, entry.getKey(), value);
      } catch (IOException e) {
        if (strictMode) {
          throw new RuntimeException("Template application failed", e);
        }
      } catch (RuntimeException e) {
        if (strictMode) {
          throw e;
        }
      }
    }
    return mapResult;
  }

  private String executeTemplateString(
      final Message<?> message, final String mapping, final boolean strictMode) {
    try {
      final Template template = templateCache.get(mapping);
      if (template == null) {
        throw new IllegalArgumentException("Template not found in cache: " + mapping);
      }
      return template.apply(message);
    } catch (IOException e) {
      if (strictMode) {
        throw new RuntimeException("Template application failed", e);
      }
    } catch (RuntimeException e) {
      if (strictMode) {
        throw e;
      }
    }
    return "";
  }

  @SuppressWarnings(UNCHECKED)
  private Object executeScript(
      final Message<?> message,
      final String script,
      final boolean dropOriginal,
      final boolean strictMode) {
    final Source source = jsSourceCache.get(script);
    Object finalResult;
    try (Context context =
        Context.newBuilder("js")
            .engine(JS_ENGINE)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(s -> true)
            .build()) {
      final Value bindings = context.getBindings("js");
      bindings.putMember("message", message);
      bindings.putMember("payload", message.getPayload());
      bindings.putMember("metadata", message.getMetadata());

      final Value scriptResult = context.eval(source);
      final Object resultObj = detachValue(scriptResult);

      if (dropOriginal) {
        finalResult = resultObj;
      } else {
        final Map<String, Object> original = MapUtils.asMutableMap(message.getPayload());
        if (resultObj instanceof Map) {
          original.putAll((Map<String, Object>) resultObj);
        }
        finalResult = original;
      }
    } catch (RuntimeException e) {
      if (strictMode) {
        throw e;
      }
      if (log.isWarnEnabled()) {
        log.warn("Script execution failed: {}", e.getMessage());
      }
      finalResult = dropOriginal ? Map.of() : message.getPayload();
    }
    return finalResult;
  }

  private Object detachValue(final Value value) {
    if (value.isNull()) {
      return null;
    }
    if (value.isNumber()) {
      return value.as(Number.class);
    }
    if (value.isString()) {
      return value.asString();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.hasArrayElements()) {
      final List<Object> list = new ArrayList<>((int) value.getArraySize());
      for (int i = 0; i < value.getArraySize(); i++) {
        list.add(detachValue(value.getArrayElement(i)));
      }
      return list;
    }
    if (value.hasMembers()) {
      final Map<String, Object> map = new HashMap<>();
      for (final String key : value.getMemberKeys()) {
        map.put(key, detachValue(value.getMember(key)));
      }
      return map;
    }
    return value.as(Object.class);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (mode == null
        || (!MODE_PROJECTION.equals(mode)
            && !MODE_TEMPLATE.equals(mode)
            && !MODE_SCRIPT.equals(mode))) {
      return Mono.error(
          new IllegalArgumentException(
              "Invalid or missing mode. Must be PROJECTION, TEMPLATE, or SCRIPT"));
    }
    if (config.get(CONFIG_MAPPING) == null) {
      return Mono.error(new IllegalArgumentException("mapping is mandatory"));
    }
    return Mono.empty();
  }
}

 ```

---
### FailureStrategy.java
Location: `java/com/infenia/yukta/plugin/core/FailureStrategy.java`

```
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
package com.infenia.yukta.plugin.core;

/** Failure strategies for loop wrappers. */
public enum FailureStrategy {
  /** Abort the workflow execution. */
  ABORT,
  /** Retry the current iteration. */
  RETRY,
  /** Skip the current iteration and move to the next. */
  SKIP,
  /** Escalate the error to the global handler (stops parent workflow). */
  ESCALATE
}

 ```

