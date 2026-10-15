// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** A processor whose behaviour depends on its type, to try the transport. */
final class FakePlugin implements ProcessorPlugin {

  /** The plugin type, which also picks the behaviour. */
  private final String type;

  /** Counts attempts that were cancelled. */
  private final AtomicInteger cancelled;

  /* default */ FakePlugin(final String type, final AtomicInteger cancelled) {
    this.type = type;
    this.cancelled = cancelled;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return switch (type) {
      case "UPPER" ->
          input.map(
              message ->
                  DefaultMessage.create(
                      UUID.randomUUID(),
                      String.valueOf(message.payload()).toUpperCase(Locale.ROOT)));
      case "SLOW" -> input.delayElements(java.time.Duration.ofMillis(1500));
      case "BOOM" -> Flux.error(new IllegalStateException("boom"));
      case "BAD" -> Flux.error(new IllegalArgumentException("bad input"));
      case "HEART" ->
          Flux.deferContextual(
              ctx -> {
                ((Runnable) ctx.get(WorkflowContext.HEARTBEAT_KEY)).run();
                return input;
              });
      case "CTX" ->
          Flux.deferContextual(
              ctx ->
                  Flux.just(
                      DefaultMessage.create(
                          UUID.randomUUID(),
                          ctx.get("executionId")
                              + "/"
                              + ctx.get("nodeId")
                              + "/"
                              + config.get("k"))));
      default -> input.concatWith(Flux.never()).doOnCancel(cancelled::incrementAndGet);
    };
  }
}
