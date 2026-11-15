// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallRateLimiter;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.agent.AgentSteeringException.Reason;
import com.infenia.yukta.service.input.ActorInfo;
import com.infenia.yukta.service.input.ActorResolver;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Checks who may send a command to an agent and hands it to the live conversation. A caller may
 * steer when it reaches the session of the conversation, has role {@code OPERATOR} or higher (a
 * supervisor needs no role) and matches a selector of the conversation's {@code steerAudience}:
 * {@code role:X} at or below the caller's role, {@code key:<name>}, {@code supervisor:<id>} or
 * {@code supervisor:*}. A key marked as an agent matches only a selector that names it. A
 * conversation the caller does not reach is as unknown as one that does not exist.
 */
@Component
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CognitiveComplexity"})
public class AgentSteering {

  private static final String ANONYMOUS = "anonymous";

  private static final Set<String> MESSAGE_ONLY = Set.of(AgentCapability.MESSAGE);

  /** Looks up conversations and the live handle of the running ones. */
  private final AgentConversationService service;

  /** Works out who sends the command. */
  private final ActorResolver actors;

  /** The size of a message. */
  private final int maxMessageBytes;

  /** Caps the messages of one caller to one conversation. */
  private final CallRateLimiter limiter;

  /**
   * Creates the steering.
   *
   * @param service looks up conversations and live handles
   * @param actors works out who sends the command
   * @param properties the {@code yukta.agents} settings
   */
  public AgentSteering(
      final AgentConversationService service,
      final ActorResolver actors,
      final YuktaProperties properties) {
    this.service = service;
    this.actors = actors;
    this.maxMessageBytes = properties.agents().maxMessageBytes();
    this.limiter = new CallRateLimiter(properties.agents().messagesPerMinute());
  }

  /**
   * Sends a message.
   *
   * @param conversationId the conversation
   * @param text the message
   * @param interrupt true to stop the agent's turn first
   * @return completes when the plugin has been handed the message
   */
  public Mono<Void> message(
      final String conversationId, final String text, final boolean interrupt) {
    return send(
        conversationId,
        interrupt ? Set.of(AgentCapability.MESSAGE, AgentCapability.INTERRUPT) : MESSAGE_ONLY,
        text.getBytes(StandardCharsets.UTF_8).length,
        true,
        actor -> new AgentCommand.Message(text, interrupt, actor));
  }

  /**
   * Interrupts the current turn.
   *
   * @param conversationId the conversation
   * @return completes when the plugin has been handed the command
   */
  public Mono<Void> interrupt(final String conversationId) {
    return send(
        conversationId, Set.of(AgentCapability.INTERRUPT), 0, false, AgentCommand.Interrupt::new);
  }

  /**
   * Ends the conversation.
   *
   * @param conversationId the conversation
   * @param outcome the outcome that overrides the last turn's, or {@code null}
   * @param reason why, or {@code null}
   * @return completes when the plugin has been handed the command
   */
  public Mono<Void> end(final String conversationId, final String outcome, final String reason) {
    return send(
        conversationId, Set.of(), 0, false, actor -> new AgentCommand.End(outcome, reason, actor));
  }

  /**
   * Whether a message sent now waits for the end of the turn.
   *
   * @param conversation the conversation
   * @param interrupt whether the message interrupts first
   * @return true when the agent is busy and cannot take a message during its turn
   */
  public static boolean willQueue(final AgentConversation conversation, final boolean interrupt) {
    final boolean busy = conversation.state() == com.infenia.yukta.plugin.agent.AgentState.RUNNING;
    return busy && !interrupt && !conversation.capabilities().contains(AgentCapability.STEER);
  }

  private Mono<Void> send(
      final String conversationId,
      final Set<String> needed,
      final int textBytes,
      final boolean limited,
      final java.util.function.Function<String, AgentCommand> command) {
    return actors
        .resolve()
        .flatMap(actor ->
            require(conversationId, actor)
                .map(conversation -> {
                  authorize(conversation, actor);
                  for (final String capability : needed) {
                    if (!conversation.capabilities().contains(capability)) {
                      throw new AgentSteeringException(
                          Reason.UNSUPPORTED,
                          "This agent cannot do that: it lacks the capability " + capability,
                          capability);
                    }
                  }
                  if (textBytes > maxMessageBytes) {
                    throw new AgentSteeringException(
                        Reason.TOO_LARGE,
                        "The message is longer than " + maxMessageBytes + " bytes",
                        null);
                  }
                  final LiveAgentConversation live =
                      service.live(conversationId).orElseThrow(() -> notLive(conversationId));
                  if (limited && !limiter.tryAcquire(actor.id() + "/" + conversationId)) {
                    throw new AgentSteeringException(
                        Reason.RATE_LIMITED, "Too many messages to this conversation", null);
                  }
                  if (!live.send(command.apply(name(actor)))) {
                    throw notLive(conversationId);
                  }
                  return conversation;
                }))
        .then();
  }

  private Mono<AgentConversation> require(final String conversationId, final ActorInfo actor) {
    return service
        .find(conversationId)
        .filter(found -> reaches(actor, found.sessionId()))
        .switchIfEmpty(
            Mono.error(() -> new NotFoundException("Conversation not found: " + conversationId)));
  }

  private static AgentSteeringException notLive(final String conversationId) {
    return new AgentSteeringException(
        Reason.NOT_LIVE, "Conversation " + conversationId + " is not running", null);
  }

  private static boolean reaches(final ActorInfo actor, final String sessionId) {
    final Set<String> sessions = actor.sessions();
    return actor.type() == ActorInfo.Type.SUPERVISOR
        || sessions == null
        || sessions.isEmpty()
        || sessions.contains(sessionId);
  }

  private static void authorize(final AgentConversation conversation, final ActorInfo actor) {
    final boolean allowed =
        switch (actor.type()) {
          case HUMAN, AGENT ->
              operator(actor)
                  && conversation.steerAudience().selectors().stream()
                      .anyMatch(selector -> human(actor, selector));
          case SUPERVISOR ->
              conversation.steerAudience().selectors().stream()
                  .anyMatch(selector ->
                      "supervisor:*".equals(selector)
                          || ("supervisor:" + actor.id()).equals(selector));
          case EXTERNAL, UNAUTHENTICATED -> false;
        };
    if (!allowed) {
      throw new AgentSteeringException(
          Reason.FORBIDDEN, "You may not send commands to this conversation", null);
    }
  }

  private static boolean operator(final ActorInfo actor) {
    return actor.role() == ApiRole.OPERATOR || actor.role() == ApiRole.ADMIN;
  }

  private static boolean human(final ActorInfo actor, final String selector) {
    if (("key:" + actor.id()).equals(selector)) {
      return true;
    }
    // an agent key matches only a selector that names it
    if (actor.type() == ActorInfo.Type.AGENT) {
      return false;
    }
    return switch (selector) {
      case "role:OPERATOR" -> operator(actor);
      case "role:ADMIN" -> actor.role() == ApiRole.ADMIN;
      default -> false;
    };
  }

  private static String name(final ActorInfo actor) {
    if (actor.type() == ActorInfo.Type.SUPERVISOR) {
      return "supervisor:" + actor.id();
    }
    return ANONYMOUS.equals(actor.id()) ? ANONYMOUS : "key:" + actor.id();
  }
}
