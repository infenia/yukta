// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.AgentConversationList;
import com.infenia.yukta.mcp.dto.AgentConversationView;
import com.infenia.yukta.mcp.dto.AgentScreen;
import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.util.LogSanitizer;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.service.agent.AgentConversationQueries;
import com.infenia.yukta.service.agent.AgentSteering;
import com.infenia.yukta.service.agent.AgentSteeringException;
import com.infenia.yukta.service.control.SupervisedControl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads and steers the conversations with AI agents for the MCP tools: the same operations as the
 * REST controller, with the caller's identity. Everything an agent wrote passes {@link
 * LogSanitizer}.
 */
@Component
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings({
  "PMD.UseObjectForClearerAPI",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects"
})
public class ConversationProvider {

  /** Most conversations one call lists. */
  public static final int MAX_CONVERSATIONS = 50;

  /** Most items one call returns. */
  public static final int MAX_ITEMS = 100;

  /** The most characters of one text. */
  private static final int MAX_TEXT = 4000;

  /** The most bytes a call types into a terminal. */
  private static final int MAX_KEYS = AgentSteering.MAX_TERMINAL_INPUT_BYTES;

  /** The named keys {@code send_agent_keys} knows, as the bytes a terminal takes. */
  private static final Map<String, String> NAMED_KEYS =
      Map.of(
          "enter", "\r",
          "esc", "\u001b",
          "tab", "\t",
          "up", "\u001b[A",
          "down", "\u001b[B",
          "right", "\u001b[C",
          "left", "\u001b[D",
          "ctrl-c", "\u0003",
          "ctrl-d", "\u0004");

  /** The keys of the data of an event that hold what a person reads, for each kind. */
  private static final Map<AgentEventKind, String[]> TEXT_KEYS =
      Map.of(
          AgentEventKind.MESSAGE, new String[] {"text"},
          AgentEventKind.RESULT, new String[] {"text", "reason", "status"},
          AgentEventKind.NOTICE, new String[] {"text"},
          AgentEventKind.TOOL, new String[] {"title", "status"},
          AgentEventKind.REQUEST, new String[] {"prompt", "state"},
          AgentEventKind.PLAN, new String[] {"entries"},
          AgentEventKind.STATE, new String[] {"state", "reason"});

  private static final Set<AgentEventKind> READABLE =
      EnumSet.complementOf(EnumSet.of(AgentEventKind.TERMINAL));

  private static final LogSanitizer SANITIZER =
      new LogSanitizer(LogSanitizer.DEFAULT_PATTERNS, MAX_TEXT);

  private final AgentConversationQueries queries;
  private final AgentSteering steering;
  private final SupervisedControl control;

  /**
   * Creates the provider.
   *
   * @param queries reads conversations
   * @param steering types into terminals
   * @param control sends messages, interrupts and ends as control commands
   */
  public ConversationProvider(
      final AgentConversationQueries queries,
      final AgentSteering steering,
      final SupervisedControl control) {
    this.queries = queries;
    this.steering = steering;
    this.control = control;
  }

  /**
   * Lists conversations.
   *
   * @param sessionId only this session, or null for all the key reaches
   * @param executionId only this run and the runs below it, or null
   * @param state comma-separated states, or null for all
   * @return the conversations, newest first
   */
  public Mono<AgentConversationList> list(
      final @Nullable String sessionId,
      final @Nullable String executionId,
      final @Nullable String state) {
    return Mono.defer(() ->
        queries
            .list(
                new AgentConversationQueries.Scope(
                    blank(sessionId),
                    blank(executionId),
                    true,
                    AgentConversationQueries.states(state),
                    reaches(sessionId)),
                MAX_CONVERSATIONS + 1)
            .collectList()
            .map(found ->
                new AgentConversationList(
                    found.stream()
                        .limit(MAX_CONVERSATIONS)
                        .map(ConversationProvider::summary)
                        .toList(),
                    found.size() > MAX_CONVERSATIONS)));
  }

  /**
   * Reads one conversation and a page of its events as items.
   *
   * @param sessionId the session of the conversation
   * @param id the conversation
   * @param afterSeq only events after this sequence number
   * @param limit most items, up to 100
   * @return the conversation and its items
   */
  public Mono<AgentConversationView> get(
      final String sessionId,
      final String id,
      final @Nullable Long afterSeq,
      final @Nullable Integer limit) {
    final Predicate<String> reaches = reaches(sessionId);
    final int size = limit == null || limit < 1 ? MAX_ITEMS : Math.min(limit, MAX_ITEMS);
    return queries
        .require(id, reaches)
        .flatMap(conversation ->
            queries
                .events(id, afterSeq == null ? 0 : afterSeq, size, READABLE, reaches)
                .map(page ->
                    new AgentConversationView(
                        summary(conversation),
                        page.events().stream().map(ConversationProvider::item).toList(),
                        page.next())));
  }

  /**
   * Sends a message.
   *
   * @param sessionId the session
   * @param id the conversation
   * @param text the message
   * @param interrupt true to stop the turn first
   * @return what was done
   */
  public Mono<ControlActionResult> message(
      final String sessionId, final String id, final String text, final boolean interrupt) {
    if (text == null || text.isBlank()) {
      return Mono.error(new IllegalArgumentException("text is required."));
    }
    return command(
        sessionId,
        id,
        "SEND_AGENT_MESSAGE",
        "Sent the message.",
        c -> control.agentMessage(c.executionId(), c.nodeId(), id, text, interrupt));
  }

  /**
   * Stops the current turn.
   *
   * @param sessionId the session
   * @param id the conversation
   * @return what was done
   */
  public Mono<ControlActionResult> interrupt(final String sessionId, final String id) {
    return command(
        sessionId,
        id,
        "INTERRUPT_AGENT",
        "Interrupted the turn.",
        c -> control.agentInterrupt(c.executionId(), c.nodeId(), id));
  }

  /**
   * Ends a conversation.
   *
   * @param sessionId the session
   * @param id the conversation
   * @param outcome success, failure or incomplete, or null for the last turn's
   * @param reason why, or null
   * @return what was done
   */
  public Mono<ControlActionResult> end(
      final String sessionId,
      final String id,
      final @Nullable String outcome,
      final @Nullable String reason) {
    return command(
        sessionId,
        id,
        "END_AGENT_CONVERSATION",
        "Ended the conversation.",
        c -> control.agentEnd(c.executionId(), c.nodeId(), id, blank(outcome), blank(reason)));
  }

  /**
   * Reads the screen of a terminal agent.
   *
   * @param sessionId the session
   * @param id the conversation
   * @return the screen as text
   */
  public Mono<AgentScreen> screen(final String sessionId, final String id) {
    return queries
        .screen(id, reaches(sessionId))
        .map(event -> {
          final Map<String, Object> data = event.data();
          return new AgentScreen(
              id,
              number(data.get("cols")),
              number(data.get("rows")),
              clean(String.valueOf(data.getOrDefault("screen", ""))));
        })
        .onErrorMap(AgentSteeringException.class, ConversationProvider::refusal);
  }

  /**
   * Types into the terminal of an agent.
   *
   * @param sessionId the session
   * @param id the conversation
   * @param text text typed as it is, or null
   * @param keys comma-separated named keys typed after the text: enter, esc, tab, up, down, left,
   *     right, ctrl-c, ctrl-d; or null
   * @return what was done
   */
  public Mono<ControlActionResult> keys(
      final String sessionId,
      final String id,
      final @Nullable String text,
      final @Nullable String keys) {
    final StringBuilder typed = new StringBuilder(text == null ? "" : text);
    if (keys != null && !keys.isBlank()) {
      try {
        Arrays.stream(keys.split(",")).map(ConversationProvider::named).forEach(typed::append);
      } catch (IllegalArgumentException e) {
        return Mono.error(e);
      }
    }
    final byte[] data = typed.toString().getBytes(StandardCharsets.UTF_8);
    if (data.length == 0 || data.length > MAX_KEYS) {
      return Mono.error(new IllegalArgumentException("Type between 1 and " + MAX_KEYS + " bytes."));
    }
    return queries
        .require(id, reaches(sessionId))
        .flatMap(c ->
            steering
                .terminalInput(id, data)
                .thenReturn(result("SEND_AGENT_KEYS", c, "Typed " + data.length + " bytes.")))
        .onErrorMap(AgentSteeringException.class, ConversationProvider::refusal);
  }

  private static String named(final String name) {
    final String bytes = NAMED_KEYS.get(name.strip().toLowerCase(java.util.Locale.ROOT));
    if (bytes == null) {
      throw new IllegalArgumentException(
          "Unknown key '"
              + name.strip()
              + "'; use "
              + NAMED_KEYS.keySet().stream().sorted().toList());
    }
    return bytes;
  }

  private Mono<ControlActionResult> command(
      final String sessionId,
      final String id,
      final String action,
      final String message,
      final Function<AgentConversation, Mono<Void>> send) {
    return queries
        .require(id, reaches(sessionId))
        .flatMap(c -> send.apply(c).thenReturn(result(action, c, message)))
        .onErrorMap(AgentSteeringException.class, ConversationProvider::refusal);
  }

  private static ControlActionResult result(
      final String action, final AgentConversation conversation, final String message) {
    return new ControlActionResult(
        action, conversation.executionId(), conversation.nodeId(), List.of(), message);
  }

  /** A refusal a tool caller can act on. */
  private static IllegalArgumentException refusal(final AgentSteeringException e) {
    return new IllegalArgumentException(
        e.reason().name().toLowerCase(java.util.Locale.ROOT) + ": " + e.getMessage(), e);
  }

  /** The caller reaches the session it names; with none named, every session its key reaches. */
  private static Predicate<String> reaches(final @Nullable String sessionId) {
    final String wanted = blank(sessionId);
    return found -> wanted == null || wanted.equals(found);
  }

  private static @Nullable String blank(final @Nullable String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static int number(final Object value) {
    return value instanceof final Number n ? n.intValue() : 0;
  }

  private static AgentConversationList.Summary summary(final AgentConversation c) {
    return new AgentConversationList.Summary(
        c.id(),
        c.sessionId(),
        c.executionId(),
        c.nodeId(),
        c.harness(),
        c.protocol().name(),
        c.model(),
        c.state().name(),
        c.outcome(),
        c.turn(),
        c.queued(),
        c.capabilities().stream().sorted().toList());
  }

  private static AgentConversationView.Item item(final AgentEvent event) {
    final Map<String, Object> data = event.data();
    final String text = text(data, TEXT_KEYS.getOrDefault(event.kind(), new String[0]));
    final Object role = data.get("role");
    return new AgentConversationView.Item(
        event.seq(), event.kind().name(), role == null ? null : String.valueOf(role), clean(text));
  }

  private static String text(final Map<String, Object> data, final String... keys) {
    final List<String> parts = new ArrayList<>();
    Arrays.stream(keys)
        .map(data::get)
        .filter(value -> value != null && !String.valueOf(value).isBlank())
        .forEach(value -> parts.add(String.valueOf(value)));
    return String.join(" ", parts);
  }

  /** Cleans agent text line by line, so a screen keeps its lines. */
  private static String clean(final String text) {
    return Arrays.stream(text.split("\n", -1))
        .map(SANITIZER::clean)
        .collect(java.util.stream.Collectors.joining("\n"));
  }
}
