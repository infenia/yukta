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
import com.infenia.yukta.plugin.exception.NoMatchingBranchException;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Branch plugin routes messages to different ports based on conditions or selectors. Supports
 * SELECT_KEY (exact match) and EXPRESSION (SpEL predicate) modes.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.CognitiveComplexity",
  "PMD.AvoidDeeplyNestedIfStmts",
  "PMD.ExceptionAsFlowControl",
  "PMD.AvoidCatchingGenericException"
})
public class BranchProcessor implements ProcessorPlugin {

  private static final String TYPE = "BRANCH";

  private static final String CONFIG_MODE = "mode";
  private static final String CONFIG_SELECTOR = "selector";
  private static final String CONFIG_CASES = "cases";
  private static final String DEF_PORT = "defaultPort";
  private static final String ALLOW_MULT = "allowMultipleMatches";
  private static final String STRICT = "strictMode";
  private static final String ERROR_PORT = "errorPort";

  private static final String MODE_SELECT_KEY = "SELECT_KEY";
  private static final String MODE_EXPRESSION = "EXPRESSION";

  private static final String ERR_PREFIX = "WorkflowExecutionException: ";
  private static final String UNCHECKED = "unchecked";

  /** Default constructor. */
  public BranchProcessor() {
    super();
  }

  @Override
  public String getDescription() {
    return "Routes messages to different ports based on conditions or selectors. Supports exact"
        + " match and SpEL predicate modes.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'SELECT_KEY' (evaluates selector and looks up in cases) or 'EXPRESSION' "
        + "(evaluates each case key as a predicate).\n"
        + "- selector: SpEL expression used in 'SELECT_KEY' mode.\n"
        + "- cases: Map where keys are match values (SELECT_KEY) or SpEL predicates (EXPRESSION), "
        + "and values are output port names.\n"
        + "- defaultPort: Optional port name if no matches are found.\n"
        + "- allowMultipleMatches: Boolean. If true, message can be routed to multiple ports. "
        + "Default is false.\n"
        + "- strictMode: Boolean. If true (default), throws exception if no branch matches and "
        + "no defaultPort is set.\n"
        + "- errorPort: Optional port name to route messages when evaluation fails. Adds "
        + "failure metadata to the message.";
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
                <svg class="w-8 h-8 text-indigo-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h8a2 2 0 012 2v10M8 17H6a2 2 0 01-2-2V5a2 2 0 012-2h8a2 2 0 012 2v2M21 21l-3-3m0 0l-3 3m3-3v-8"/>
                </svg>
                <div class="text-[10px] text-slate-500 font-medium uppercase">Branch</div>
            </div>
            """,
            140,
            80));
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);
    final String errorPort = (String) config.get(ERROR_PORT);

    final Set<String> ports = new HashSet<>(cases.values());
    if (defaultPort != null && !defaultPort.isBlank()) {
      ports.add(defaultPort);
    }
    if (errorPort != null && !errorPort.isBlank()) {
      ports.add(errorPort);
    }

    if (ports.isEmpty()) {
      return List.of("default");
    }
    return new ArrayList<>(ports);
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (MODE_SELECT_KEY.equals(mode)) {
      SpelUtils.preParse((String) config.get(CONFIG_SELECTOR));
    } else if (MODE_EXPRESSION.equals(mode)) {
      final Map<String, String> cases = (Map<String, String>) config.get(CONFIG_CASES);
      if (cases != null) {
        cases.keySet().forEach(SpelUtils::preParse);
      }
    }
    return Mono.empty();
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final String selector = (String) config.get(CONFIG_SELECTOR);
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);
    final boolean allowMultiple = (Boolean) config.getOrDefault(ALLOW_MULT, false);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);
    final String errorPort = (String) config.get(ERROR_PORT);

    return input.flatMap(
        message ->
            Flux.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  final List<String> matchedPorts = new ArrayList<>();

                  try {
                    evaluateBranches(mode, selector, cases, allowMultiple, message, matchedPorts);

                    if (matchedPorts.isEmpty()) {
                      if (defaultPort != null) {
                        matchedPorts.add(defaultPort);
                      } else if (strictMode) {
                        throw new NoMatchingBranchException(
                            ERR_PREFIX
                                + "No matching branch found for message "
                                + message.getMessageId()
                                + " and no default port configured");
                      }
                    }

                    return Flux.fromIterable(matchedPorts)
                        .map(port -> message.withSourcePort(port).withAddedHistory(nodeId));

                  } catch (final Exception exception) {
                    return handleException(message, nodeId, errorPort, strictMode, exception);
                  }
                }));
  }

  private Flux<Message<?>> handleException(
      final Message<?> message,
      final String nodeId,
      final String errorPort,
      final boolean strictMode,
      final Exception exception) {
    if (log.isErrorEnabled()) {
      log.error(
          "Branch evaluation failed for message {}: {}",
          message.getMessageId(),
          exception.getMessage());
    }
    if (errorPort != null) {
      return Flux.just(
          message
              .withSourcePort(errorPort)
              .withAddedHistory(nodeId)
              .withFailure(null, "Branch evaluation failed", exception.getMessage()));
    }
    if (!strictMode) {
      return Flux.empty();
    }
    return Flux.error(
        exception instanceof NoMatchingBranchException
            ? exception
            : new RuntimeException(ERR_PREFIX + "Branch evaluation failed", exception));
  }

  private void evaluateBranches(
      final String mode,
      final String selector,
      final Map<String, String> cases,
      final boolean allowMultiple,
      final Message<?> message,
      final List<String> matchedPorts) {
    if (MODE_SELECT_KEY.equals(mode)) {
      final Object result = SpelUtils.evaluateSync(selector, message);
      if (result != null) {
        final String port = cases.get(result.toString());
        if (port != null) {
          matchedPorts.add(port);
        }
      }
    } else if (MODE_EXPRESSION.equals(mode)) {
      for (final Map.Entry<String, String> entry : cases.entrySet()) {
        final Boolean match = SpelUtils.evaluateSync(entry.getKey(), message);
        if (Boolean.TRUE.equals(match)) {
          matchedPorts.add(entry.getValue());
          if (!allowMultiple) {
            break;
          }
        }
      }
    }
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (mode == null || (!MODE_SELECT_KEY.equals(mode) && !MODE_EXPRESSION.equals(mode))) {
      return Mono.error(
          new IllegalArgumentException(
              "Invalid or missing mode. Must be SELECT_KEY or EXPRESSION"));
    }
    if (MODE_SELECT_KEY.equals(mode) && (config.get(CONFIG_SELECTOR) == null)) {
      return Mono.error(new IllegalArgumentException("selector is mandatory for SELECT_KEY mode"));
    }
    final Map<String, String> cases = (Map<String, String>) config.get(CONFIG_CASES);
    if (cases == null || cases.isEmpty()) {
      return Mono.error(new IllegalArgumentException("cases map is mandatory and cannot be empty"));
    }
    return Mono.empty();
  }
}
