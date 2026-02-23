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
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.NoMatchingBranchException;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  private static final String MODE_SELECT_KEY = "SELECT_KEY";
  private static final String MODE_EXPRESSION = "EXPRESSION";

  private static final String ERR_PREFIX = "WorkflowExecutionException: ";

  /** Default constructor. */
  public BranchProcessor() {
    super();
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Void> initialize(final Map<String, Object> config) {
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
  @SuppressWarnings("unchecked")
  public Flux<Message> process(final Flux<Message> input, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final String selector = (String) config.get(CONFIG_SELECTOR);
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);
    final boolean allowMultiple = (Boolean) config.getOrDefault(ALLOW_MULT, false);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);

    return input.flatMap(
        message -> {
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
                        + message.id()
                        + " and no default port configured");
              }
            }

            return Flux.fromIterable(matchedPorts).map(message::withSourcePort);

          } catch (final Exception e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Branch evaluation failed for message {}: {}", message.id(), e.getMessage());
            }
            return Flux.error(
                e.getMessage() != null && e.getMessage().startsWith(ERR_PREFIX)
                    ? e
                    : new RuntimeException(ERR_PREFIX + "Branch evaluation failed", e));
          }
        });
  }

  private void evaluateBranches(
      final String mode,
      final String selector,
      final Map<String, String> cases,
      final boolean allowMultiple,
      final Message message,
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
  @SuppressWarnings("unchecked")
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
