// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** {@link StubPlugins}: what each stub emits. */
class StubPluginsTest {

  private static final Map<String, Object> CONFIG =
      Map.of(
          "emit",
          List.of(Map.of("payload", "a", "port", "success"), Map.of("payload", "b"), "junk"));

  private final StubPlugins plugins = new StubPlugins();

  @Test
  void triggerEmitsTheStubbedMessagesWithTheirPorts() {
    final List<Message<?>> out = plugins.stubTrigger().start(CONFIG).collectList().block();

    assertThat(out).extracting(m -> (Object) m.payload()).containsExactly("a", "b");
    assertThat(out).extracting(Message::sourcePort).containsExactly("success", null);
    assertThat(plugins.stubTrigger().start(Map.of()).collectList().block()).isEmpty();
    assertThat(plugins.stubTrigger().start(Map.of("emit", "x")).collectList().block()).isEmpty();
  }

  @Test
  void processorEmitsTheStubForEveryInput_andDeclaresTheCommonPorts() {
    final Flux<Message<?>> input =
        Flux.just(
            DefaultMessage.create(UUID.randomUUID(), "1"),
            DefaultMessage.create(UUID.randomUUID(), "2"));

    assertThat(plugins.stubProcessor().process(input, CONFIG).collectList().block()).hasSize(4);
    assertThat(plugins.stubProcessor().getOutputPorts(CONFIG))
        .containsExactly("default", "success", "failure");
    assertThat(plugins.stubProcessor().getType()).isEqualTo(StubPlugins.PROCESSOR);
  }

  @Test
  void terminalTakesEverythingAndEmitsNothing() {
    final Flux<Message<?>> input = Flux.just(DefaultMessage.create(UUID.randomUUID(), "1"));

    plugins.stubTerminal().consume(input, Map.of()).block();

    assertThat(plugins.stubTerminal().getType()).isEqualTo(StubPlugins.TERMINAL);
    assertThat(plugins.stubTrigger().getType()).isEqualTo(StubPlugins.TRIGGER);
  }
}
