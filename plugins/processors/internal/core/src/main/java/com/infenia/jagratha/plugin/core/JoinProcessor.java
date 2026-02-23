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
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.JoinTimeoutException;
import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.service.join.JoinStore;
import com.infenia.jagratha.service.join.JoinStore.JoinConfig;
import com.infenia.jagratha.service.join.JoinStore.JoinResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Synchronizes multiple incoming execution paths by waiting for criteria to be met. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods"})
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
  public String getType() {
    return TYPE;
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
  public Flux<Message> process(final Flux<Message> input, final Map<String, Object> config) {
    final JoinConfig joinConfig = createJoinConfig(config);
    final String correlationKey = (String) config.get(CFG_CORR_KEY);
    final boolean strictMode = (Boolean) config.getOrDefault(CFG_STRICT, true);

    return input
        .groupBy(message -> getCorrelationId(message, correlationKey))
        .flatMap(group -> processGroup(group, joinConfig, config, strictMode));
  }

  private Flux<Message> processGroup(
      final Flux<Message> group,
      final JoinConfig joinConfig,
      final Map<String, Object> config,
      final boolean strictMode) {
    final Object corrId = ((reactor.core.publisher.GroupedFlux<?, Message>) group).key();
    final String errorPort = (String) config.get(CFG_ERR_PORT);
    final String latePort = (String) config.get(CFG_LATE_PORT);

    return Flux.deferContextual(
        ctx -> {
          final String sessionId = ctx.getOrDefault("sessionId", "unknown");
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          final String key = sessionId + ":" + nodeId + ":" + corrId;

          final Flux<Message> processed =
              group.flatMap(
                  message ->
                      joinStore
                          .addMessage(key, message.sourceNodeId(), message, joinConfig)
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

  private Flux<Message> handleTimeout(
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

  private Object getCorrelationId(final Message message, final String expression) {
    if (expression == null || expression.isBlank()) {
      return message.traceId();
    }
    return SpelUtils.evaluateSync(expression, message);
  }

  private Flux<Message> handleJoinResult(
      final JoinResult result,
      final Message current,
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

  private Flux<Message> handleOverflow(
      final Message message, final String port, final boolean strict) {
    if (port != null) {
      return Flux.just(createErrorMessage(message, "JoinStore overflow", port));
    }
    if (strict) {
      return Flux.error(new RuntimeException("JoinStore overflow"));
    }
    return Flux.empty();
  }

  private Message createMergedMessage(
      final Map<String, Message> messages, final Message last, final Map<String, Object> config) {
    final String strategy = (String) config.getOrDefault(CFG_MERGE, STRAT_ARRAY);
    @SuppressWarnings("unchecked")
    final List<String> ancestors = (List<String>) config.get(CFG_ANCESTORS);

    final Object payload =
        switch (strategy) {
          case STRAT_ARRAY -> mergeAsArray(ancestors, messages);
          case STRAT_MERGE -> MergeUtils.mergeObjects(ancestors, messages);
          case STRAT_LATEST -> last.payload();
          default -> last.payload();
        };
    return new Message(
        UUID.randomUUID(), last.traceId(), last.metadata(), payload, Instant.now(), null, null);
  }

  private List<Object> mergeAsArray(final List<String> ancestors, final Map<String, Message> messages) {
    if (ancestors == null || ancestors.isEmpty()) {
      return messages.values().stream().map(Message::payload).toList();
    }
    return ancestors.stream()
        .map(messages::get)
        .filter(java.util.Objects::nonNull)
        .map(Message::payload)
        .toList();
  }

  private Message createErrorMessage(
      final Message original, final String error, final String port) {
    final Map<String, Object> metadata = new ConcurrentHashMap<>(original.metadata());
    metadata.put("error_message", error);
    return new Message(
        original.id(),
        original.traceId(),
        metadata,
        original.payload(),
        original.timestamp(),
        port,
        original.sourceNodeId());
  }

  private Message createTimeoutErrorMessage(final Object corrId, final String port) {
    final UUID traceId = (corrId instanceof UUID) ? (UUID) corrId : UUID.randomUUID();
    return new Message(
        UUID.randomUUID(),
        traceId,
        Map.of("error_message", "Join timed out"),
        "Timeout",
        Instant.now(),
        port,
        null);
  }
}
