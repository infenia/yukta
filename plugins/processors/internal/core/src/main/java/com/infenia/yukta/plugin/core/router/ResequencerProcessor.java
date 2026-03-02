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
package com.infenia.yukta.plugin.core.router;

import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.resequence.ResequencerStore;
import com.infenia.yukta.service.resequence.ResequencerStore.ResequenceConfig;
import com.infenia.yukta.service.resequence.ResequencerStore.ResequenceResult;
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

/**
 * Stateful message router used to get a stream of related but out-of-sequence messages back into
 * the correct order.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class ResequencerProcessor implements ProcessorPlugin {

  private static final String TYPE = "RESEQUENCER";

  private static final String CFG_SEQ_PATH = "sequencePath";
  private static final String CFG_SEQ_ID_PATH = "sequenceIdPath";
  private static final String CFG_START_INDEX = "startIndex";
  private static final String CFG_TIMEOUT_MS = "timeoutMs";
  private static final String CFG_MAX_PENDING = "maxPending";
  private static final String CFG_ERROR_PORT = "errorPort";

  private static final String DEF_SEQ_PATH = "#metadata.sequenceNumber";
  private static final String DEF_SEQ_ID_PATH = "#metadata.sequenceId";
  private static final int DEF_START_INDEX = 1;
  private static final long DEF_TIMEOUT_MS = 60_000L;
  private static final int DEF_MAX_PENDING = 1000;

  private static final String PORT_DEFAULT = "default";
  private static final String HDR_BROKEN_SEQ = "yukta.resequencer.sequence_broken";

  @Autowired private ResequencerStore resequencerStore;

  /** Default constructor. */
  public ResequencerProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Collects out-of-sequence messages in an internal buffer and only publishes them "
        + "once a consecutive sequence is obtained.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- sequencePath: SpEL expression to locate the sequence number. Default:"
        + " '#metadata.sequenceNumber'.\n"
        + "- sequenceIdPath: SpEL expression to locate the sequence ID. Default:"
        + " '#metadata.sequenceId'.\n"
        + "- startIndex: The expected starting number of the sequence. Default: 1.\n"
        + "- timeoutMs: Max time to wait for a missing message before flushing. Default:"
        + " 60,000ms.\n"
        + "- maxPending: Max number of messages to buffer per sequence. Default: 1000.\n"
        + "- errorPort: Port to route late arrivals, duplicates, or overflowed messages.";
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
            <div class="flex items-center w-full h-full bg-cyan-50/50 border-2 border-cyan-100 rounded-xl px-4 gap-3">
                <div class="flex-shrink-0 w-8 h-8 bg-cyan-100/50 rounded-lg flex items-center justify-center text-cyan-500">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h10M4 18h16" />
                        <circle cx="18" cy="12" r="2" fill="currentColor" />
                    </svg>
                </div>
                <div class="flex flex-col min-w-0">
                    <div class="text-[10px] text-cyan-600 font-bold uppercase tracking-wider leading-none mb-1">Resequencer</div>
                    <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final List<String> ports = new ArrayList<>();
    ports.add(PORT_DEFAULT);
    final String errorPort = (String) config.get(CFG_ERROR_PORT);
    if (errorPort != null && !errorPort.isBlank()) {
      ports.add(errorPort);
    }
    return ports;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    SpelUtils.preParse((String) config.getOrDefault(CFG_SEQ_PATH, DEF_SEQ_PATH));
    SpelUtils.preParse((String) config.getOrDefault(CFG_SEQ_ID_PATH, DEF_SEQ_ID_PATH));
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String seqPath = (String) config.getOrDefault(CFG_SEQ_PATH, DEF_SEQ_PATH);
    final String seqIdPath = (String) config.getOrDefault(CFG_SEQ_ID_PATH, DEF_SEQ_ID_PATH);
    final ResequenceConfig resConfig = createResequenceConfig(config);

    return Flux.deferContextual(
        ctx -> {
          final String sessionId = ctx.getOrDefault("sessionId", "unknown");
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");

          final Flux<Message<?>> incoming =
              input.flatMap(
                  msg -> {
                    final Map<String, Object> vars =
                        Map.of(
                            "payload", msg.getPayload(),
                            "metadata", msg.getMetadata());
                    final Number seqNum = SpelUtils.evaluateSync(seqPath, msg, vars);
                    final Object seqId = SpelUtils.evaluateSync(seqIdPath, msg, vars);
                    final String key =
                        sessionId + ":" + nodeId + ":" + (seqId != null ? seqId : PORT_DEFAULT);

                    if (seqNum == null) {
                      return handleInvalid(
                          msg, "MISSING_SEQUENCE_NUMBER", resConfig.errorPort(), nodeId);
                    }

                    return resequencerStore
                        .addMessage(key, seqNum.longValue(), msg, resConfig)
                        .flatMapMany(
                            res ->
                                handleResequenceResult(
                                    res, nodeId, (String) config.get(CFG_ERROR_PORT)));
                  });

          final String keyPrefix = sessionId + ":" + nodeId + ":";
          final Flux<Message<?>> async =
              resequencerStore
                  .getAsyncResults()
                  .filter(res -> res.key().startsWith(keyPrefix))
                  .takeUntilOther(input.then())
                  .flatMap(
                      res ->
                          handleResequenceResult(res, nodeId, (String) config.get(CFG_ERROR_PORT)));

          final Flux<Message<?>> remaining =
              input.thenMany(
                  Flux.defer(
                      () ->
                          resequencerStore
                              .flushAll(keyPrefix)
                              .flatMap(
                                  res ->
                                      handleResequenceResult(
                                          res, nodeId, (String) config.get(CFG_ERROR_PORT)))));

          return Flux.merge(incoming, async).concatWith(remaining);
        });
  }

  private Flux<Message<?>> handleResequenceResult(
      final ResequenceResult res, final String nodeId, final String errorPort) {
    return switch (res.status()) {
      case COMPLETED ->
          Flux.fromIterable(res.messages())
              .map(m -> prepareMessage(m, nodeId, false, PORT_DEFAULT));
      case TIMEOUT_JUMP ->
          Flux.fromIterable(res.messages())
              .map(
                  m ->
                      prepareMessage(
                          m,
                          nodeId,
                          true,
                          (errorPort != null && !errorPort.isBlank()) ? errorPort : PORT_DEFAULT));
      case LATE_ARRIVAL, DUPLICATE, OVERFLOW ->
          handleInvalid(res.messages().get(0), res.failureReason(), errorPort, nodeId);
      case WAITING -> Flux.empty();
    };
  }

  private Flux<Message<?>> handleInvalid(
      final Message<?> msg, final String reason, final String errorPort, final String nodeId) {
    if (errorPort != null && !errorPort.isBlank()) {
      return Flux.just(
          prepareMessage(msg, nodeId, false, errorPort).withFailure(null, reason, null));
    }
    return Flux.empty();
  }

  private Message<?> prepareMessage(
      final Message<?> msg, final String nodeId, final boolean broken, final String port) {
    Message<?> result = msg.withAddedHistory(nodeId).withSourcePort(port);
    if (broken) {
      result = result.withHeader(HDR_BROKEN_SEQ, true);
    }
    return result;
  }

  private ResequenceConfig createResequenceConfig(final Map<String, Object> config) {
    return new ResequenceConfig(
        ((Number) config.getOrDefault(CFG_START_INDEX, DEF_START_INDEX)).longValue(),
        ((Number) config.getOrDefault(CFG_TIMEOUT_MS, DEF_TIMEOUT_MS)).longValue(),
        ((Number) config.getOrDefault(CFG_MAX_PENDING, DEF_MAX_PENDING)).intValue(),
        (String) config.get(CFG_ERROR_PORT));
  }

  @Override
  public Duration getDefaultTimeout() {
    return Duration.ofMinutes(5);
  }
}
