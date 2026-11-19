// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The plugins {@link WorkflowTestRunner} swaps in for the nodes of a workflow test. Each emits the
 * messages of its {@code emit} config (a list of {@code {payload, port}}), so no real side effect
 * runs.
 */
@Configuration
public class StubPlugins {

  /** Plugin type of a stubbed trigger. */
  public static final String TRIGGER = "TEST_STUB_TRIGGER";

  /** Plugin type of a stubbed processor. */
  public static final String PROCESSOR = "TEST_STUB_PROCESSOR";

  /** Plugin type of a stubbed terminal. */
  public static final String TERMINAL = "TEST_STUB_TERMINAL";

  /** The messages a stub emits. */
  @UtilityClass
  /* default */ static class StubMessages {
    /* default */ static Flux<Message<?>> messages(final Map<String, Object> config) {
      final Object emit = config.get("emit");
      final List<?> entries = emit instanceof List<?> list ? list : List.of();
      return Flux.fromIterable(entries)
          .filter(Map.class::isInstance)
          .map(entry -> {
            final Map<?, ?> map = (Map<?, ?>) entry;
            final Message<?> message = DefaultMessage.create(UUID.randomUUID(), map.get("payload"));
            return map.get("port") instanceof String port ? message.withSourcePort(port) : message;
          });
    }
  }

  /**
   * The stub trigger.
   *
   * @return the plugin
   */
  @Bean
  public TriggerPlugin stubTrigger() {
    return new TriggerPlugin() {
      @Override
      public String getType() {
        return TRIGGER;
      }

      @Override
      public Flux<Message<?>> start(final Map<String, Object> config) {
        return StubMessages.messages(config);
      }
    };
  }

  /**
   * The stub processor: for every input it emits the stubbed messages.
   *
   * @return the plugin
   */
  @Bean
  public ProcessorPlugin stubProcessor() {
    return new ProcessorPlugin() {
      @Override
      public String getType() {
        return PROCESSOR;
      }

      @Override
      public List<String> getOutputPorts(final Map<String, Object> config) {
        return List.of("default", "success", "failure");
      }

      @Override
      public Flux<Message<?>> process(
          final Flux<Message<?>> input, final Map<String, Object> config) {
        return input.concatMap(_ -> StubMessages.messages(config));
      }
    };
  }

  /**
   * The stub terminal: it takes what arrives and does nothing.
   *
   * @return the plugin
   */
  @Bean
  public TerminalPlugin stubTerminal() {
    return new TerminalPlugin() {
      @Override
      public String getType() {
        return TERMINAL;
      }

      @Override
      public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
        return input.then();
      }
    };
  }
}
