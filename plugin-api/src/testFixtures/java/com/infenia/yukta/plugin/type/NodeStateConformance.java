// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.type;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Checks that two nodes of one processor type do not share state. Every plugin test can call it: it
 * runs two nodes with different configurations, feeds them in turns, and fails if either node's
 * output differs from what the same node produces alone.
 */
public final class NodeStateConformance {

  private NodeStateConformance() {}

  /**
   * Run two nodes of {@code plugin} side by side and compare each with its solo run.
   *
   * @param plugin the plugin under test
   * @param configA config of node A
   * @param configB config of node B, different from A
   * @param inputsA payloads sent to node A
   * @param inputsB payloads sent to node B
   * @throws AssertionError if the interleaved outputs differ from the solo outputs
   */
  public static void assertNodesDoNotShareState(
      final ProcessorPlugin plugin,
      final Map<String, Object> configA,
      final Map<String, Object> configB,
      final List<?> inputsA,
      final List<?> inputsB) {
    final List<Object> soloA = run(plugin, configA, inputsA);
    final List<Object> soloB = run(plugin, configB, inputsB);

    final Sinks.Many<Message<?>> sinkA = Sinks.many().unicast().onBackpressureBuffer();
    final Sinks.Many<Message<?>> sinkB = Sinks.many().unicast().onBackpressureBuffer();
    final CompletableFuture<List<Object>> outA = collect(plugin, configA, sinkA);
    final CompletableFuture<List<Object>> outB = collect(plugin, configB, sinkB);
    for (int i = 0; i < Math.max(inputsA.size(), inputsB.size()); i++) {
      if (i < inputsA.size()) {
        sinkA.tryEmitNext(message(inputsA.get(i)));
      }
      if (i < inputsB.size()) {
        sinkB.tryEmitNext(message(inputsB.get(i)));
      }
    }
    sinkA.tryEmitComplete();
    sinkB.tryEmitComplete();

    check("A", soloA, outA.join());
    check("B", soloB, outB.join());
  }

  private static List<Object> run(
      final ProcessorPlugin plugin, final Map<String, Object> config, final List<?> inputs) {
    final List<Object> out = new ArrayList<>();
    forNode(plugin, config)
        .process(Flux.fromIterable(inputs).map(NodeStateConformance::message), config)
        .toIterable()
        .forEach(m -> out.add(m.payload()));
    return out;
  }

  private static CompletableFuture<List<Object>> collect(
      final ProcessorPlugin plugin,
      final Map<String, Object> config,
      final Sinks.Many<Message<?>> sink) {
    return forNode(plugin, config)
        .process(sink.asFlux(), config)
        .map(m -> (Object) m.payload())
        .collectList()
        .toFuture();
  }

  private static ProcessorPlugin forNode(
      final ProcessorPlugin plugin, final Map<String, Object> config) {
    return (ProcessorPlugin) plugin.forNode(config);
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private static void check(final String node, final List<Object> solo, final List<Object> both) {
    if (!solo.equals(both)) {
      throw new AssertionError(
          "Node "
              + node
              + " output changed when another node ran: alone "
              + solo
              + ", with "
              + both);
    }
  }
}
