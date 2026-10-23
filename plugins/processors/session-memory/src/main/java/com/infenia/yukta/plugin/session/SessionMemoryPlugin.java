// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Processor plugin giving a workflow chat memory.
 *
 * <p>Place a {@code load} node before llm-call and an {@code append} node after it. {@code load}
 * attaches {@code history} to the payload and records the user's turn; {@code append} records the
 * assistant's turn. Compaction is a workflow pattern: branch on history length and summarize with
 * an llm-call node.
 */
@SuppressWarnings({"PMD.CommentRequired", "PMD.UseConcurrentHashMap", "PMD.TooManyMethods"})
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionMemoryPlugin implements ProcessorPlugin {

  private static final String TYPE = "SESSION_MEMORY";
  private static final String MODE = "mode";
  private static final String SESSION_ID = "sessionId";
  private static final String MESSAGE = "message";
  private static final String ASSISTANT = "assistant";
  private static final String MAX_TURNS = "maxTurns";
  private static final String HISTORY = "history";
  private static final String USER_TURN = "sessionUserTurn";
  private static final String PORT_DEFAULT = "default";
  private static final int DEFAULT_MAX_TURNS = 20;

  private final SessionStore store;
  private final VariableResolver resolver;

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Chat memory per session id and workflow: 'load' attaches history to the payload, "
            + "'append' records the user turn and the assistant turn together, 'delete' removes "
            + "the history.",
        usagePattern(),
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            "mode",
            true,
            null,
            "load adds history; append records the user turn and the reply; delete removes it",
            "load",
            "append",
            "delete"),
        ConfigField.optional(
            "sessionId",
            ConfigField.Type.STRING,
            null,
            "Conversation id (append defaults to ${metadata.sessionId})"),
        ConfigField.optional(
            "message", ConfigField.Type.EXPRESSION, null, "The user turn to record (load)"),
        ConfigField.optional(
            "assistant",
            ConfigField.Type.EXPRESSION,
            null,
            "The reply to record (append; default ${payload.content})"),
        ConfigField.optional("maxTurns", ConfigField.Type.NUMBER, 20, "Turns of history kept"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  private static String usagePattern() {
    // CHECKSTYLE.OFF: Indentation
    // CHECKSTYLE.OFF: TextBlockGoogleStyleFormatting
    return """
    Configuration Parameters (values support variable expressions):

    - mode: load | append | delete (required)
    - maxTurns: Integer, turns kept per session and returned by load (default: 20)

    mode: load (place before llm-call)
    - sessionId: required, e.g. "${payload.sessionId}"
    - message: required, the user's text, e.g. "${payload.message}"
    - Reads the stored turns. The user turn is kept in the message and stored by append, so a
      failed model call leaves no unanswered turn. Output payload = input payload
      (a Map; other payloads are wrapped as {input: payload}) plus "history": [{role, content}].
      Message metadata gets "sessionId" so a later append node can find the session.
      Feed it to llm-call: messages "${payload.history}", prompt "${payload.message}".

    mode: append (place after llm-call)
    - sessionId: default "${metadata.sessionId}"
    - assistant: the reply text, default "${payload.content}"
    - Records the user turn from load and the assistant turn together; the message passes through
      with the default port.

    mode: delete
    - sessionId: required. Deletes the stored history of the session (for a user's request to
      erase their chat); the message passes through with the default port.

    History is kept per workflow: another workflow that knows the session id cannot read it.
    It is in memory and lost on restart unless the server keeps it in files or PostgreSQL.
    """;
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return Flux.defer(
        () -> {
          final Mode mode = Mode.from(config.get(MODE));
          // one message at a time: each load must see the turns the messages before it stored
          return input.concatMap(
              message ->
                  switch (mode) {
                    case LOAD -> load(message, config);
                    case APPEND -> append(message, config);
                    default -> delete(message, config);
                  });
        });
  }

  private Mono<Message<?>> load(final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(resolve(config.get(SESSION_ID), message), resolve(config.get(MESSAGE), message))
        .map(
            t -> {
              final String sessionId = t.getT1();
              final int maxTurns = maxTurns(config);
              final Map<String, Object> payload = new LinkedHashMap<>();
              if (message.payload() instanceof final Map<?, ?> map) {
                map.forEach((key, value) -> payload.put(String.valueOf(key), value));
              } else {
                payload.put("input", message.payload());
              }
              payload.put(HISTORY, store.history(key(message, sessionId), maxTurns));
              final Map<String, Object> metadata = new HashMap<>(message.metadata());
              metadata.put(SESSION_ID, sessionId);
              metadata.put(USER_TURN, t.getT2());
              return DefaultMessage.from(message, payload)
                  .withMetadata(metadata)
                  .withSourcePort(PORT_DEFAULT);
            });
  }

  private Mono<Message<?>> append(final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(
            resolve(config.getOrDefault(SESSION_ID, "${metadata.sessionId}"), message),
            resolve(config.getOrDefault(ASSISTANT, "${payload.content}"), message))
        .map(
            t -> {
              final List<Map<String, String>> turns = new ArrayList<>();
              if (message.metadata().get(USER_TURN) instanceof final String user) {
                turns.add(Map.of("role", "user", "content", user));
              }
              turns.add(Map.of("role", ASSISTANT, "content", t.getT2()));
              store.append(key(message, t.getT1()), turns, maxTurns(config));
              return message.withSourcePort(PORT_DEFAULT);
            });
  }

  private Mono<Message<?>> delete(final Message<?> message, final Map<String, Object> config) {
    return resolve(config.get(SESSION_ID), message)
        .map(
            sessionId -> {
              store.delete(key(message, sessionId));
              return message.withSourcePort(PORT_DEFAULT);
            });
  }

  /** History is kept per workflow, so a workflow cannot read another's conversation by its id. */
  private static String key(final Message<?> message, final String sessionId) {
    return message.workflowId() + ":" + sessionId;
  }

  private Mono<String> resolve(final Object value, final Message<?> message) {
    return resolver.resolve(value, message).map(Object::toString);
  }

  private static int maxTurns(final Map<String, Object> config) {
    return MapUtils.convert(config.getOrDefault(MAX_TURNS, DEFAULT_MAX_TURNS), Integer.class);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.<Void>fromRunnable(() -> checkConfig(config))
        .doOnError(
            e ->
                log.atWarn()
                    .log("Session memory configuration validation failed: {}", e.getMessage()));
  }

  private static void checkConfig(final Map<String, Object> config) {
    final Mode mode = Mode.from(config.get(MODE));
    final boolean missing =
        switch (mode) {
          case LOAD -> config.get(SESSION_ID) == null || config.get(MESSAGE) == null;
          case DELETE -> config.get(SESSION_ID) == null;
          default -> false;
        };
    if (missing) {
      throw new IllegalArgumentException(mode + " requires sessionId (and message for load)");
    }
    if (config.get(MAX_TURNS) instanceof final Number number && number.intValue() <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive, got: " + number);
    }
  }
}
