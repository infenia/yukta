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
import com.infenia.yukta.plugin.UiDesign;
import com.infenia.yukta.service.aggregate.AggregateStore;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateConfig;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateResult;
import com.infenia.yukta.util.SpelUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final String CFG_MAX_PENDING = "maxPending";
  private static final String CFG_EMIT_TIMEOUT = "emitOnTimeout";
  private static final String CFG_NULL_POLICY = "nullPolicy";
  private static final String CFG_ERROR_PORT = "errorPort";

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
        + "- maxPending: Maximum number of windows to keep in memory (default 1000).\n"
        + "- emitOnTimeout: Boolean. If true (default), partial windows are emitted on timeout.\n"
        + "- nullPolicy: 'IGNORE' (default), 'FAIL', or 'ZERO'.\n"
        + "- errorPort: Port to route expired windows when emitOnTimeout is false.";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1 relative">
                <svg class="w-8 h-8 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                          d="M4 6h16M4 10h16M4 14h16M4 18h16"/>
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                          d="M9 6v12M15 6v12" opacity="0.2"/>
                    <rect x="7" y="5" width="10" height="14" rx="1" stroke-width="2" class="fill-blue-50"/>
                </svg>
                <div class="text-[10px] text-slate-500 font-medium uppercase">Aggregator</div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final List<String> ports = new ArrayList<>();
    ports.add("default");
    final String errorPort = (String) config.get(CFG_ERROR_PORT);
    if (errorPort != null && !errorPort.isBlank()) {
      ports.add(errorPort);
    }
    return ports;
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
    final String errorPort = (String) config.get(CFG_ERROR_PORT);

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
                        .flatMapMany(res -> handleResult(res, nodeId, errorPort));
                  });

          final Flux<Message<?>> async =
              getAsyncMessages(keyPrefix, nodeId, errorPort, input.then());

          final Flux<Message<?>> remaining =
              input.thenMany(
                  Flux.defer(
                      () ->
                          aggregateStore
                              .flushAll(keyPrefix)
                              .map(res -> createMessage(res, nodeId, "default"))));

          return Flux.merge(incoming, async).concatWith(remaining);
        });
  }

  @SuppressWarnings("PMD.LawOfDemeter")
  private Flux<Message<?>> getAsyncMessages(
      final String keyPrefix,
      final String nodeId,
      final String errorPort,
      final Mono<Void> completion) {
    return aggregateStore
        .getAsyncResults()
        .filter(res -> res.key().startsWith(keyPrefix))
        .takeUntilOther(completion)
        .flatMap(res -> handleResult(res, nodeId, errorPort));
  }

  @Override
  public Mono<Void> shutdown(final Map<String, Object> config) {
    return aggregateStore.flushAll().then();
  }

  private Flux<Message<?>> handleResult(
      final AggregateResult result, final String nodeId, final String errorPort) {
    if (result.status() == AggregateResult.Status.WAITING) {
      return Flux.empty();
    }
    if (result.status() == AggregateResult.Status.EXPIRED) {
      if (errorPort != null && !errorPort.isBlank()) {
        return Flux.just(createMessage(result, nodeId, errorPort));
      }
      return Flux.empty();
    }
    return Flux.just(createMessage(result, nodeId, "default"));
  }

  private Message<?> createMessage(
      final AggregateResult result, final String nodeId, final String port) {
    return com.infenia.yukta.plugin.DefaultMessage.from(result.lastMessage(), result.result())
        .withSourcePort(port)
        .withAddedHistory(nodeId);
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
