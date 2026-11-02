// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** A recording gateway, a provider for it and a list checkpoint store for the tests. */
final class InputFakes {

  /** The context of a running node. */
  static final Context NODE =
      Context.of("sessionId", "s1", "workflowId", "w1", "executionId", "e1", "nodeId", "n1");

  private InputFakes() {}

  /** Records every spec and answers with what the function says. */
  static final class Gateway implements InputGateway {
    final List<InputRequestSpec> asked = new ArrayList<>();
    private final Function<InputRequestSpec, Mono<InputResponse>> answer;

    Gateway(final Function<InputRequestSpec, Mono<InputResponse>> answer) {
      this.answer = answer;
    }

    @Override
    public Mono<InputResponse> ask(final InputRequestSpec spec) {
      asked.add(spec);
      return answer.apply(spec);
    }
  }

  static InputResponse response(final String decision, final String text) {
    return new InputResponse(
        "req-1",
        decision,
        text,
        Map.of(),
        new Responder(Responder.Type.HUMAN, "op"),
        null,
        null,
        Instant.EPOCH);
  }

  static Gateway answering(final String decision, final String text) {
    return new Gateway(_ -> Mono.just(response(decision, text)));
  }

  static ObjectProvider<InputGateway> provider(final InputGateway gateway) {
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    if (gateway != null) {
      factory.addBean("gateway", gateway);
    }
    return factory.getBeanProvider(InputGateway.class);
  }

  /** A provider with no resolver bean. */
  static ObjectProvider<com.infenia.yukta.plugin.util.VariableResolver> noResolver() {
    return new StaticListableBeanFactory()
        .getBeanProvider(com.infenia.yukta.plugin.util.VariableResolver.class);
  }

  /** A checkpoint store in a list. */
  static final class ListStore implements NodeCheckpointStore {
    final List<Message<?>> kept = new ArrayList<>();
    boolean failing;

    @Override
    public Mono<Void> save(final String executionId, final String nodeId, final Message<?> m) {
      return failing
          ? Mono.error(new IllegalStateException("disk full"))
          : Mono.fromRunnable(() -> kept.add(m));
    }

    @Override
    public Mono<Message<?>> get(final String executionId, final String nodeId) {
      return getAll(executionId, nodeId).takeLast(1).next();
    }

    @Override
    public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
      return failing
          ? Flux.error(new IllegalStateException("disk gone"))
          : Flux.fromIterable(List.copyOf(kept));
    }

    @Override
    public void clear(final String executionId) {
      kept.clear();
    }
  }
}
