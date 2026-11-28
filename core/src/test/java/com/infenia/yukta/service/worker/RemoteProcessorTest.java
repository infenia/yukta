// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RemoteProcessorTest {

  private static final Map<String, Object> CONFIG = Map.of("url", "http://x");
  private static final String TYPE = "HTTP_REQUEST";
  private static final String POOL = "default";

  private final ProcessorPlugin local = mock(ProcessorPlugin.class);
  private final RemoteNodeExecutor executor = mock(RemoteNodeExecutor.class);
  private final Message<?> signal = mock(Message.class);
  private RemoteProcessor remote;

  @BeforeEach
  void setUp() {
    when(local.getType()).thenReturn(TYPE);
    remote = new RemoteProcessor(local, executor, POOL);
  }

  @Test
  void process_sendsTheCallToWorkerOfThePool() {
    final Message<?> out = mock(Message.class);
    final Flux<Message<?>> input = Flux.empty();
    when(executor.run(TYPE, POOL, input, CONFIG)).thenReturn(Flux.just(out));

    StepVerifier.create(remote.process(input, CONFIG)).expectNext(out).verifyComplete();

    verify(executor).run(TYPE, POOL, input, CONFIG);
  }

  @Test
  void process_noFreeWorkerWithLocalFallback_runsOnTheLocalPlugin() {
    final Message<?> out = mock(Message.class);
    final Flux<Message<?>> input = Flux.empty();
    final Map<String, Object> config = placedConfig("LOCAL");
    when(executor.run(TYPE, POOL, input, config))
        .thenReturn(Flux.error(new NoFreeWorkerException("no slot")));
    when(local.process(input, config)).thenReturn(Flux.just(out));

    StepVerifier.create(remote.process(input, config)).expectNext(out).verifyComplete();
  }

  @Test
  void process_noFreeWorkerWithoutLocalFallback_failsAndNeverRunsLocally() {
    final Flux<Message<?>> input = Flux.empty();
    final Map<String, Object> config = placedConfig("FAIL");
    when(executor.run(TYPE, POOL, input, config))
        .thenReturn(Flux.error(new NoFreeWorkerException("no slot")));

    StepVerifier.create(remote.process(input, config))
        .expectError(NoFreeWorkerException.class)
        .verify();

    verify(local, org.mockito.Mockito.never()).process(input, config);
  }

  @Test
  void category_isTheLocalPluginsCategory() {
    when(local.getCategory()).thenReturn(PluginCategory.PROCESSOR);

    assertThat(remote.getCategory()).isEqualTo(PluginCategory.PROCESSOR);
  }

  @Test
  void controlSignal_isHandledByTheLocalPlugin() {
    final Message<?> reply = mock(Message.class);
    when(local.onControlSignal(signal)).thenReturn(Mono.just(reply));

    StepVerifier.create(remote.onControlSignal(signal)).expectNext(reply).verifyComplete();
  }

  @Test
  void forNode_withoutPerNodeStateIsTheSameStandIn() {
    when(local.forNode(CONFIG)).thenReturn(local);

    assertThat(remote.forNode(CONFIG)).isSameAs(remote);
  }

  @Test
  void forNode_withPerNodeStateStillSendsTheCallToWorker() {
    final ProcessorPlugin perNode = mock(ProcessorPlugin.class);
    final Message<?> out = mock(Message.class);
    when(local.forNode(CONFIG)).thenReturn(perNode);
    when(perNode.getType()).thenReturn(TYPE);
    when(executor.run(
            ArgumentMatchers.eq(TYPE),
            ArgumentMatchers.eq(POOL),
            ArgumentMatchers.any(),
            ArgumentMatchers.any()))
        .thenReturn(Flux.just(out));

    final Plugin bound = remote.forNode(CONFIG);

    assertThat(bound).isInstanceOf(RemoteProcessor.class).isNotSameAs(remote);
    StepVerifier.create(((ProcessorPlugin) bound).process(Flux.empty(), CONFIG))
        .expectNext(out)
        .verifyComplete();
    verify(executor)
        .run(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.eq(POOL),
            ArgumentMatchers.any(),
            ArgumentMatchers.eq(CONFIG));
  }

  private static Map<String, Object> placedConfig(final String fallback) {
    return Map.of("engine", Map.of("placement", Map.of("fallback", fallback)));
  }
}
