// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Builds plugin guide templates. Encapsulates template code generation logic with no external
 * dependencies.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
@UtilityClass
public class PluginTemplateBuilder {

  /** Constant for selecting all plugin types. */
  private static final String ALL_TYPE = "all";

  /**
   * Build plugin code templates filtered by type.
   *
   * @param templateType filter type: "trigger", "processor", "terminal", or "all"
   * @return map of template code keyed by type
   */
  public static Map<String, String> buildTemplates(final String templateType) {
    final Map<String, String> templates = new LinkedHashMap<>();
    final String type = templateType == null || templateType.isBlank() ? ALL_TYPE : templateType;

    if (ALL_TYPE.equalsIgnoreCase(type) || "trigger".equalsIgnoreCase(type)) {
      templates.put(
          "trigger",
          """
          import com.infenia.yukta.message.DefaultMessage;
          import com.infenia.yukta.message.Message;
          import com.infenia.yukta.plugin.type.TriggerPlugin;
          import java.util.Map;
          import java.util.UUID;
          import org.springframework.stereotype.Component;
          import reactor.core.publisher.Flux;

          @Component
          public class MyTriggerPlugin implements TriggerPlugin {
            @Override
            public String getType() { return "MY_TRIGGER"; }

            @Override
            public Flux<Message<?>> start(final Map<String, Object> config) {
              // Emit the messages that start the workflow
              return Flux.just(DefaultMessage.create(UUID.randomUUID(), "triggered"));
            }
          }
          """);
    }

    if (ALL_TYPE.equalsIgnoreCase(type) || "processor".equalsIgnoreCase(type)) {
      templates.put(
          "processor",
          """
          import com.infenia.yukta.message.DefaultMessage;
          import com.infenia.yukta.message.Message;
          import com.infenia.yukta.plugin.type.ProcessorPlugin;
          import java.util.Map;
          import org.springframework.stereotype.Component;
          import reactor.core.publisher.Flux;

          @Component
          public class MyProcessorPlugin implements ProcessorPlugin {
            @Override
            public String getType() { return "MY_PROCESSOR"; }

            @Override
            public Flux<Message<?>> process(
                final Flux<Message<?>> input, final Map<String, Object> config) {
              // Transform each message; DefaultMessage.from keeps the original's identity
              return input.map(message -> DefaultMessage.from(message, "processed"));
            }
          }
          """);
    }

    if (ALL_TYPE.equalsIgnoreCase(type) || "terminal".equalsIgnoreCase(type)) {
      templates.put(
          "terminal",
          """
          import com.infenia.yukta.message.Message;
          import com.infenia.yukta.plugin.type.TerminalPlugin;
          import java.util.Map;
          import org.springframework.stereotype.Component;
          import reactor.core.publisher.Flux;
          import reactor.core.publisher.Mono;

          @Component
          public class MyTerminalPlugin implements TerminalPlugin {
            @Override
            public String getType() { return "MY_TERMINAL"; }

            @Override
            public Mono<Void> consume(
                final Flux<Message<?>> input, final Map<String, Object> config) {
              // Handle every message that reaches the end of the workflow
              return input.doOnNext(message -> { /* use message.payload() */ }).then();
            }
          }
          """);
    }

    return templates;
  }
}
