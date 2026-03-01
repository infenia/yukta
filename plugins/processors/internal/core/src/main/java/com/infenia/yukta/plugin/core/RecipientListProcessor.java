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

import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Recipient List processor propagates a single inbound message to a set of dynamically specified
 * recipients.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidCatchingGenericException",
  "PMD.CognitiveComplexity"
})
public class RecipientListProcessor implements ProcessorPlugin {

  private static final String TYPE = "RECIPIENT_LIST";

  private static final String CONFIG_MODE = "mode";
  private static final String CONFIG_RECIPIENTS = "recipients";
  private static final String CONFIG_EXPRESSION = "expression";
  private static final String CONFIG_SRC_FIELD = "sourceField";
  private static final String CONFIG_PARALLEL = "parallel";
  private static final String CONFIG_STRICT = "strictMode";
  private static final String CONFIG_ERROR_PORT = "errorPort";

  private static final String MODE_STATIC = "STATIC";
  private static final String MODE_DYNAMIC = "DYNAMIC";
  private static final String MODE_EXTERNAL = "EXTERNAL";

  private static final String ERR_PREFIX = "WorkflowExecutionException: ";

  /** Default constructor. */
  public RecipientListProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Propagates a single inbound message to a set of dynamically specified recipients"
        + " (ports).";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'STATIC', 'DYNAMIC', or 'EXTERNAL'.\n"
        + "- recipients: List of fixed port names (for STATIC mode).\n"
        + "- expression: SpEL expression to compute port names (for DYNAMIC mode).\n"
        + "- sourceField: Header or payload path to extract the list from (for EXTERNAL mode).\n"
        + "- parallel: Boolean. If true (default), dispatches concurrently.\n"
        + "- errorPort: Optional port for evaluation failures.\n"
        + "- strictMode: Boolean. If true (default), throws exception on failure.";
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
            <div class="flex flex-col items-center justify-center h-full space-y-1 relative">
                <svg class="w-8 h-8 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 12h4m0 0l4-8m-4 8l4 8m4-8h4m-4-8h4m-4 16h4"/>
                </svg>
                <div class="text-[10px] text-slate-500 font-medium uppercase">Recipient List</div>
            </div>
            """,
            140,
            80));
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_DYNAMIC);
    if (MODE_STATIC.equals(mode)) {
      final List<String> recipients = (List<String>) config.get(CONFIG_RECIPIENTS);
      final List<String> ports = new ArrayList<>();
      if (recipients != null) {
        ports.addAll(recipients);
      }
      final String errorPort = (String) config.get(CONFIG_ERROR_PORT);
      if (errorPort != null && !errorPort.isBlank()) {
        ports.add(errorPort);
      }
      return ports.isEmpty() ? List.of("default") : ports;
    }
    return List.of("*");
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_DYNAMIC);
    if (MODE_STATIC.equals(mode)) {
      if (config.get(CONFIG_RECIPIENTS) == null) {
        return Mono.error(
            new IllegalArgumentException("recipients list is mandatory for STATIC mode"));
      }
    } else if (MODE_DYNAMIC.equals(mode)) {
      if (config.get(CONFIG_EXPRESSION) == null) {
        return Mono.error(new IllegalArgumentException("expression is mandatory for DYNAMIC mode"));
      }
    } else if (MODE_EXTERNAL.equals(mode)) {
      if (config.get(CONFIG_SRC_FIELD) == null) {
        return Mono.error(
            new IllegalArgumentException("sourceField is mandatory for EXTERNAL mode"));
      }
    } else {
      return Mono.error(new IllegalArgumentException("Invalid mode: " + mode));
    }
    return Mono.empty();
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_DYNAMIC);
    if (MODE_DYNAMIC.equals(mode)) {
      SpelUtils.preParse((String) config.get(CONFIG_EXPRESSION));
    } else if (MODE_EXTERNAL.equals(mode)) {
      SpelUtils.preParse((String) config.get(CONFIG_SRC_FIELD));
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_DYNAMIC);
    final boolean parallel = (Boolean) config.getOrDefault(CONFIG_PARALLEL, true);
    final boolean strictMode = (Boolean) config.getOrDefault(CONFIG_STRICT, true);
    final String errorPort = (String) config.get(CONFIG_ERROR_PORT);

    if (parallel) {
      return input.flatMap(message -> dispatch(message, mode, config, strictMode, errorPort));
    }
    return input.concatMap(message -> dispatch(message, mode, config, strictMode, errorPort));
  }

  private Flux<Message<?>> dispatch(
      final Message<?> message,
      final String mode,
      final Map<String, Object> config,
      final boolean strictMode,
      final String errorPort) {

    return Flux.deferContextual(
        ctx -> {
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          try {
            final List<String> recipients = computeRecipients(message, mode, config);
            if (recipients == null || recipients.isEmpty()) {
              return Flux.empty();
            }
            return Flux.fromIterable(recipients)
                .map(port -> message.withSourcePort(port).withAddedHistory(nodeId));
          } catch (final Exception e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Recipient computation failed for message {}: {}",
                  message.getMessageId(),
                  e.getMessage());
            }
            if (errorPort != null && !errorPort.isBlank()) {
              return Flux.just(
                  message
                      .withSourcePort(errorPort)
                      .withAddedHistory(nodeId)
                      .withFailure(null, "Recipient computation failed", e.getMessage()));
            }
            if (!strictMode) {
              return Flux.empty();
            }
            return Flux.error(new RuntimeException(ERR_PREFIX + "Recipient computation failed", e));
          }
        });
  }

  @SuppressWarnings("unchecked")
  private List<String> computeRecipients(
      final Message<?> message, final String mode, final Map<String, Object> config) {
    return switch (mode) {
      case MODE_STATIC -> (List<String>) config.get(CONFIG_RECIPIENTS);
      case MODE_DYNAMIC -> evaluateExpression(message, (String) config.get(CONFIG_EXPRESSION));
      case MODE_EXTERNAL -> evaluateExpression(message, (String) config.get(CONFIG_SRC_FIELD));
      default -> List.of();
    };
  }

  private List<String> evaluateExpression(final Message<?> message, final String expression) {
    final Map<String, Object> variables =
        Map.of(
            "payload", message.getPayload(),
            "metadata", message.getMetadata(),
            "headers", message.getMetadata());
    final Object result = SpelUtils.evaluateSync(expression, message, variables);
    return coerceToList(result);
  }

  @SuppressWarnings("unchecked")
  private List<String> coerceToList(final Object result) {
    if (result == null) {
      return List.of();
    }
    if (result instanceof List) {
      return (List<String>) result;
    }
    if (result instanceof Collection) {
      final List<String> list = new ArrayList<>();
      for (final Object o : (Collection<?>) result) {
        list.add(String.valueOf(o));
      }
      return list;
    }
    if (result.getClass().isArray()) {
      final int length = Array.getLength(result);
      final List<String> list = new ArrayList<>(length);
      for (int i = 0; i < length; i++) {
        list.add(String.valueOf(Array.get(result, i)));
      }
      return list;
    }
    return List.of(String.valueOf(result));
  }
}
