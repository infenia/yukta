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
import com.infenia.yukta.plugin.WorkflowExecutionException;
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
 * Routing Slip processor computes a list of required processing steps (ports) for an incoming
 * message, attaches that list to the message metadata as a "Routing Slip," and initiates the
 * sequence by routing the message to the first port on the list.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
public class RoutingSlipProcessor implements ProcessorPlugin {

  private static final String TYPE = "routing-slip";

  private static final String CONFIG_MODE = "mode";
  private static final String CONFIG_SLIP_PATH = "slipPath";
  private static final String CONFIG_TABLE = "routingTable";
  private static final String CONFIG_EXPRESSION = "expression";
  private static final String CONFIG_ERROR_PORT = "errorPort";
  private static final String CONFIG_STRICT = "strictMode";

  private static final String MODE_STATIC = "STATIC";
  private static final String MODE_DYNAMIC = "DYNAMIC";

  private static final String DEFAULT_SLIP_PATH = "yukta.routing_slip";
  private static final String INDEX_KEY = "yukta.routing_slip_index";

  /** Default constructor. */
  public RoutingSlipProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Computes and attaches a routing slip to the message and initiates the sequence.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'STATIC' or 'DYNAMIC' (default).\n"
        + "- slipPath: Metadata key for the slip (default: 'yukta.routing_slip').\n"
        + "- routingTable: List of fixed port names (for STATIC mode).\n"
        + "- expression: SpEL expression to compute port names (for DYNAMIC mode).\n"
        + "- errorPort: Optional port for computation failures.\n"
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
                <svg class="w-8 h-8 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
                </svg>
                <div class="text-[10px] text-slate-500 font-medium uppercase">Routing Slip</div>
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
      final List<String> routingTable = (List<String>) config.get(CONFIG_TABLE);
      final List<String> ports = new ArrayList<>();
      if (routingTable != null) {
        ports.addAll(routingTable);
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
      if (config.get(CONFIG_TABLE) == null) {
        return Mono.error(
            new IllegalArgumentException("routingTable is mandatory for STATIC mode"));
      }
    } else if (MODE_DYNAMIC.equals(mode)) {
      if (config.get(CONFIG_EXPRESSION) == null) {
        return Mono.error(new IllegalArgumentException("expression is mandatory for DYNAMIC mode"));
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
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONFIG_MODE, MODE_DYNAMIC);
    final String slipPath = (String) config.getOrDefault(CONFIG_SLIP_PATH, DEFAULT_SLIP_PATH);
    final boolean strictMode = (Boolean) config.getOrDefault(CONFIG_STRICT, true);
    final String errorPort = (String) config.get(CONFIG_ERROR_PORT);

    return input.flatMap(
        message -> dispatch(message, mode, slipPath, config, strictMode, errorPort));
  }

  private Mono<Message<?>> dispatch(
      final Message<?> message,
      final String mode,
      final String slipPath,
      final Map<String, Object> config,
      final boolean strictMode,
      final String errorPort) {

    return Mono.deferContextual(
        ctx -> {
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          try {
            final List<String> slip = computeSlip(message, mode, config);
            if (slip == null || slip.isEmpty()) {
              return handleFailure(
                  message,
                  nodeId,
                  "Empty or null routing slip computed",
                  null,
                  strictMode,
                  errorPort);
            }

            final String firstPort = slip.get(0);
            return Mono.just(
                message
                    .withHeader(slipPath, slip)
                    .withHeader(INDEX_KEY, 0)
                    .withSourcePort(firstPort)
                    .withAddedHistory(nodeId));
          } catch (final Exception e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Routing slip computation failed for message {}: {}",
                  message.getMessageId(),
                  e.getMessage());
            }
            return handleFailure(
                message,
                nodeId,
                "Routing slip computation failed",
                e.getMessage(),
                strictMode,
                errorPort);
          }
        });
  }

  private Mono<Message<?>> handleFailure(
      final Message<?> message,
      final String nodeId,
      final String reason,
      final String detail,
      final boolean strictMode,
      final String errorPort) {

    if (errorPort != null && !errorPort.isBlank()) {
      return Mono.just(
          message
              .withSourcePort(errorPort)
              .withAddedHistory(nodeId)
              .withFailure(null, reason, detail));
    }
    if (!strictMode) {
      return Mono.empty();
    }
    final String fullReason = detail != null ? reason + ": " + detail : reason;
    return Mono.error(new WorkflowExecutionException(fullReason));
  }

  @SuppressWarnings("unchecked")
  private List<String> computeSlip(
      final Message<?> message, final String mode, final Map<String, Object> config) {
    return switch (mode) {
      case MODE_STATIC -> coerceToList(config.get(CONFIG_TABLE));
      case MODE_DYNAMIC -> evaluateExpression(message, (String) config.get(CONFIG_EXPRESSION));
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
