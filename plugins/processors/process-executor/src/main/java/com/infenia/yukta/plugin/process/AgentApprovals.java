// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKeys;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimits;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Slf4j
final class AgentApprovals {

  /** The requester named on every request. */
  private static final String REQUESTER = "AGENT_SESSION";

  /** Longest part of the request details kept in the context. */
  private static final int CONTEXT_VALUE_MAX_BYTES = 1000;

  /** What a node configures about approvals. */
  record Settings(InputRisk risk, Duration ttl, Audience audience) {}

  private final AgentPreset.Interactive talk;
  private final Settings settings;
  private final ObjectMapper json;
  private final Supplier<@Nullable InputGateway> gateway;
  private final InputIdentity who;
  private final Message<?> message;
  private final String harness;
  private final ProcessExecutorGateway.StdinWriter stdin;
  private final List<Disposable> pending = new java.util.concurrent.CopyOnWriteArrayList<>();

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
  @SuppressWarnings("PMD.ExcessiveParameterList")
  AgentApprovals(
      final AgentPreset.Interactive talk,
      final Settings settings,
      final ObjectMapper json,
      final Supplier<@Nullable InputGateway> gateway,
      final InputIdentity who,
      final Message<?> message,
      final String harness,
      final ProcessExecutorGateway.StdinWriter stdin) {
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
  static String userMessage(final JsonNode template, final String prompt, final ObjectMapper json) {
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
  void handle(final AgentOutput.Request request) {
    Mono<String> reply;
    try {
      reply =
          InputAsk.ask(gateway, spec(request))
              .map(
                  answer ->
                      "approve".equalsIgnoreCase(answer.decision())
                          ? allow(request)
                          : deny(
                              request,
                              "Rejected"
                                  + (answer.rationale() == null ? "" : ": " + answer.rationale())));
    } catch (RuntimeException e) {
      reply = Mono.just(deny(request, "Could not ask for approval: " + e.getMessage()));
    }
    pending.add(
        reply
            .onErrorResume(
                e -> {
                  log.atWarn().setCause(e).log("Approval of {} not given", request.tool());
                  return Mono.just(deny(request, "Not approved: " + e.getMessage()));
                })
            .subscribe(stdin::write));
  }

  /** Stops waiting for answers that are still open (the run ended or was cancelled). */
  void dispose() {
    pending.forEach(Disposable::dispose);
  }

  private InputRequestSpec spec(final AgentOutput.Request request) {
    final String text =
        "Agent ("
            + harness
            + ") wants to use "
            + request.tool()
            + (request.description().isBlank() ? "" : ": " + request.description());
    final Map<String, Object> context = new LinkedHashMap<>();
    context.put("tool", request.tool());
    context.put("input", InputAsk.clean(request.input().toString(), CONTEXT_VALUE_MAX_BYTES));
    context.put("harness", harness);
    return new InputRequestSpec(
        InputKeys.of(who.sessionId(), who.workflowId(), who.nodeId(), message.id(), request.id()),
        InputKind.APPROVAL,
        InputAsk.clean(text, InputLimits.PROMPT_MAX_BYTES),
        context,
        List.of(),
        null,
        settings.risk(),
        settings.audience(),
        settings.ttl(),
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
