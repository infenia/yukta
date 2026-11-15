// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputCancelledException;
import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKeys;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimits;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns what a running harness asks for (a tool it wants to use) into an approval on the input
 * request system and writes the answer back to the harness. Whoever the audience names, a person, a
 * supervisor or an agent, answers; no answer in time, a refusal or any failure refuses the tool.
 *
 * <p>The conversation of the run shows each request as a {@code REQUEST} event that points at the
 * input request, and is {@code WAITING} while any request is open.
 */
@Slf4j
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports", "PMD.UseConcurrentHashMap"})
public final class AgentRequests {

  /** The requester named on every request. */
  private static final String REQUESTER = "AGENT_SESSION";

  /** The count of open requests when the first one opens. */
  private static final int FIRST_OPEN = 1;

  /** Longest part of the request details kept in the context. */
  private static final int CONTEXT_VALUE_MAX_BYTES = 1000;

  private final AgentPreset.Interactive talk;
  private final @Nullable Settings settings;
  private final ObjectMapper json;
  private final Supplier<@Nullable InputGateway> gateway;
  private final InputIdentity who;
  private final Message<?> message;
  private final String harness;
  private final ProcessExecutorGateway.StdinWriter stdin;
  private final AgentConversationHandle conversation;

  /** How many requests are open; the conversation waits while there is one. */
  private final AtomicInteger open = new AtomicInteger();

  private final List<Disposable> pending = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** What a node configures about approvals. */
  public record Settings(InputRisk risk, Duration ttl, Audience audience) {}

  /**
   * Creates the approvals of one run.
   *
   * @param talk how the harness' requests and replies look
   * @param settings risk, time to answer and who may answer
   * @param json the JSON mapper
   * @param gateway the input request system, found at use time
   * @param who the run and node asking
   * @param message the message being worked on
   * @param harness the harness id, shown to the answerer
   * @param stdin where the answers go
   */
  /* default */ AgentRequests(
      final AgentPreset.Interactive talk,
      final @Nullable Settings settings,
      final ObjectMapper json,
      final Supplier<@Nullable InputGateway> gateway,
      final InputIdentity who,
      final Message<?> message,
      final String harness,
      final ProcessExecutorGateway.StdinWriter stdin) {
    this(
        talk,
        settings,
        json,
        gateway,
        who,
        message,
        harness,
        stdin,
        AgentConversationHandle.none());
  }

  /**
   * Creates the approvals of one run that records a conversation.
   *
   * @param talk how the harness' requests and replies look
   * @param settings risk, time to answer and who may answer
   * @param json the JSON mapper
   * @param gateway the input request system, found at use time
   * @param who the run and node asking
   * @param message the message being worked on
   * @param harness the harness id, shown to the answerer
   * @param stdin where the answers go
   * @param conversation where the {@code REQUEST} events and the waiting state go
   */
  /* default */ AgentRequests(
      final AgentPreset.Interactive talk,
      final @Nullable Settings settings,
      final ObjectMapper json,
      final Supplier<@Nullable InputGateway> gateway,
      final InputIdentity who,
      final Message<?> message,
      final String harness,
      final ProcessExecutorGateway.StdinWriter stdin,
      final AgentConversationHandle conversation) {
    this.conversation = conversation;
    this.talk = talk;
    this.settings = settings;
    this.json = json;
    this.gateway = gateway;
    this.who = who;
    this.message = message;
    this.harness = harness;
    this.stdin = stdin;
  }

  /**
   * The message that carries the prompt to a harness that takes it on stdin.
   *
   * @param template the preset's message; a string {@code {prompt}} is replaced
   * @param prompt the prompt
   * @param json the JSON mapper
   * @return the message as one line of JSON
   */
  /* default */ static String userMessage(
      final JsonNode template, final String prompt, final ObjectMapper json) {
    return withPrompt(template, prompt, json).toString();
  }

  private static JsonNode withPrompt(
      final JsonNode node, final String prompt, final ObjectMapper json) {
    if (node.isString() && "{prompt}".equals(node.asString())) {
      return json.getNodeFactory().stringNode(prompt);
    }
    if (node.isObject()) {
      final ObjectNode copy = json.createObjectNode();
      node.properties().forEach(e -> copy.set(e.getKey(), withPrompt(e.getValue(), prompt, json)));
      return copy;
    }
    return node;
  }

  /**
   * Asks about one request and writes the answer when it arrives.
   *
   * @param request what the harness wants
   */
  /* default */ void handle(final AgentOutput.Request request) {
    final Settings asked = settings;
    if (asked == null) {
      // approvals are off for this node: the agent asks, the answer is no
      stdin.write(deny(request, "approvals are off for this node"));
      return;
    }
    final String prompt = prompt(request);
    conversation.emit(AgentEventKind.REQUEST, request.id(), opened(request, prompt));
    if (open.incrementAndGet() == FIRST_OPEN) {
      conversation.state(AgentState.WAITING, "approval");
    }
    pending.add(
        Mono.defer(() -> InputAsk.ask(gateway, spec(request, prompt, asked)))
            .doOnNext(answer -> answered(request, answer))
            .map(answer ->
                "approve".equalsIgnoreCase(answer.decision())
                    ? allow(request)
                    : deny(
                        request,
                        "Rejected" + (answer.rationale() == null ? "" : ": " + answer.rationale())))
            .onErrorResume(e -> {
              log.atWarn().setCause(e).log("Approval of {} not given", request.tool());
              conversation.emit(AgentEventKind.REQUEST, request.id(), ended(e));
              return Mono.just(deny(request, "Not approved: " + e.getMessage()));
            })
            .doFinally(signal -> {
              if (open.decrementAndGet() == 0) {
                conversation.state(AgentState.RUNNING, null);
              }
            })
            .subscribe(stdin::write));
  }

  /**
   * Writes the line that stops the turn.
   *
   * @return false when the harness has no interrupt line
   */
  /* default */ boolean interrupt() {
    if (!talk.canInterrupt()) {
      return false;
    }
    final AgentOutput.Request id =
        new AgentOutput.Request(
            java.util.UUID.randomUUID().toString(), "", json.getNodeFactory().nullNode(), "");
    stdin.write(render(talk.interrupt(), id, ""));
    return true;
  }

  /**
   * Writes a message of a user to the agent.
   *
   * @param text the message
   */
  /* default */ void say(final String text) {
    stdin.write(userMessage(talk.userMessage(), text, json));
  }

  /** The {@code REQUEST} event of a request that was just raised. */
  private Map<String, Object> opened(final AgentOutput.Request request, final String prompt) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("inputKind", InputKind.APPROVAL.name());
    data.put("prompt", prompt);
    data.put("state", "open");
    data.put("tool", request.tool());
    return data;
  }

  /** The input request has an id only once the broker made it, so the answer carries it. */
  private void answered(final AgentOutput.Request request, final InputResponse answer) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("inputRequestId", answer.requestId());
    data.put("state", "answered");
    data.put("decision", answer.decision());
    final Responder who = answer.responder();
    data.put("by", who.type().name().toLowerCase(java.util.Locale.ROOT) + ":" + who.id());
    conversation.emit(AgentEventKind.REQUEST, request.id(), data);
  }

  /** The {@code REQUEST} event of a request that nobody answered. */
  private static Map<String, Object> ended(final Throwable error) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("state", error instanceof InputExpiredException ? "expired" : "cancelled");
    data.put(
        "reason",
        error instanceof InputExpiredException || error instanceof InputCancelledException
            ? error.getClass().getSimpleName()
            : String.valueOf(error.getMessage()));
    return data;
  }

  /** Stops waiting for answers that are still open (the run ended or was cancelled). */
  /* default */ void dispose() {
    pending.forEach(Disposable::dispose);
  }

  private String prompt(final AgentOutput.Request request) {
    return InputAsk.clean(
        "Agent ("
            + harness
            + ") wants to use "
            + request.tool()
            + (request.description().isBlank() ? "" : ": " + request.description()),
        InputLimits.PROMPT_MAX_BYTES);
  }

  private InputRequestSpec spec(
      final AgentOutput.Request request, final String text, final Settings asked) {
    final Map<String, Object> context = new LinkedHashMap<>();
    context.put("tool", request.tool());
    context.put("input", InputAsk.clean(request.input().toString(), CONTEXT_VALUE_MAX_BYTES));
    context.put("harness", harness);
    return new InputRequestSpec(
        InputKeys.of(who.sessionId(), who.workflowId(), who.nodeId(), message.id(), request.id()),
        InputKind.APPROVAL,
        text,
        context,
        List.of(),
        null,
        asked.risk(),
        asked.audience(),
        asked.ttl(),
        TimeoutAction.FAIL,
        null,
        null,
        REQUESTER);
  }

  private String allow(final AgentOutput.Request request) {
    return render(talk.allow(), request, "");
  }

  private String deny(final AgentOutput.Request request, final String reason) {
    return render(talk.deny(), request, reason);
  }

  private String render(
      final JsonNode template, final AgentOutput.Request request, final String message) {
    return fill(template, request, message).toString();
  }

  private JsonNode fill(
      final JsonNode node, final AgentOutput.Request request, final String message) {
    if (node.isString()) {
      return switch (node.asString()) {
        case "{id}" -> json.getNodeFactory().stringNode(request.id());
        case "{input}" -> request.input();
        case "{message}" -> json.getNodeFactory().stringNode(message);
        default -> node;
      };
    }
    if (node.isObject()) {
      final ObjectNode copy = json.createObjectNode();
      node.properties().forEach(e -> copy.set(e.getKey(), fill(e.getValue(), request, message)));
      return copy;
    }
    return node;
  }
}
