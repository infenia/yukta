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
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.util.SpelUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluates a boolean condition against the input message and routes to 'true' or 'false' ports.
 * Supports SpEL expressions and high-performance non-blocking evaluation.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidThrowingRawExceptionTypes",
  "PMD.AvoidCatchingGenericException"
})
public class GuardProcessor implements ProcessorPlugin {

  private static final String TYPE = "GUARD";
  private static final String PORT_TRUE = "true";
  private static final String PORT_FALSE = "false";
  private static final String CONFIG_CONDITION = "condition";
  private static final String STRICT = "strictMode";
  private static final String ERROR_PORT = "errorPort";

  /** Default constructor. */
  public GuardProcessor() {
    super();
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory for Guard plugin"));
    }
    SpelUtils.preParse(condition);
    return Mono.empty();
  }

  @Override
  public Flux<Message> process(final Flux<Message> input, final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);
    final String errorPort = (String) config.get(ERROR_PORT);

    return input.map(
        message -> {
          try {
            final Boolean result = SpelUtils.evaluateSync(condition, message);
            final String port = (result != null && result) ? PORT_TRUE : PORT_FALSE;
            return message.withSourcePort(port);
          } catch (final Exception e) {
            if (log.isErrorEnabled()) {
              log.error(
                  "Guard evaluation failed for condition [{}]: {}", condition, e.getMessage());
            }
            if (errorPort != null) {
              final Map<String, Object> metadata = new ConcurrentHashMap<>(message.metadata());
              metadata.put("error_message", e.getMessage());
              return new Message(
                  message.id(),
                  message.traceId(),
                  metadata,
                  message.payload(),
                  message.timestamp(),
                  errorPort,
                  message.sourceNodeId());
            }
            if (!strictMode) {
              return message.withSourcePort(PORT_FALSE);
            }
            throw new RuntimeException(
                "WorkflowExecutionException: Guard evaluation failed for condition: " + condition,
                e);
          }
        });
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory"));
    }
    return Mono.empty();
  }
}
