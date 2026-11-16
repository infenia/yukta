// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.process.ProcessExecutionResult;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts a harness that speaks JSON lines for one message and reads how it ended, with approvals
 * when asked for. What the harness does is written to the conversation of the run: the prompt as a
 * message of the user, the text and tool calls of the agent, its requests, the result, and at the
 * end the outcome. The run log gets the same lines as before conversations existed.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // local maps, never shared
public final class JsonlAdapter implements AgentAdapter {

  /** The item of the conversation that holds the prompt. */
  private static final String PROMPT_ITEM = "prompt";

  private final ProcessExecutorGateway gateway;
  private final ObjectProvider<InputGateway> inputs;
  private final ObjectMapper json;

  /** How often a conversation tells the engine that its node is alive. */
  private final Duration beat;

  /**
   * Creates the adapter.
   *
   * @param gateway starts the process
   * @param inputs the input request system, for approvals; found at use time
   * @param json the JSON mapper
   * @param beat how often an open conversation tells the engine that its node is alive
   */
  public JsonlAdapter(
      final ProcessExecutorGateway gateway,
      final ObjectProvider<InputGateway> inputs,
      final ObjectMapper json,
      final Duration beat) {
    this.beat = beat;
    this.gateway = gateway;
    this.inputs = inputs;
    this.json = json;
  }

  @Override
  public AgentProtocol protocol() {
    return AgentProtocol.JSONL;
  }

  @Override
  public Mono<Map<String, Object>> run(final AgentRun run) {
    return Mono.defer(() -> {
      final AgentConversationHandle conversation = run.handle();
      final AgentOutput output =
          new AgentOutput(
              run.preset().output(), run.talk(), new LogMirror(run.log()), conversation, json);
      conversation.emit(AgentEventKind.MESSAGE, PROMPT_ITEM, prompt(run.prompt()));
      conversation.state(AgentState.RUNNING, null);
      final AtomicReference<AgentChat> chat = new AtomicReference<>();
      final Mono<ProcessExecutionResult> ended =
          run.talk() == null
              ? gateway.executeForResult(run.spec(), output)
              : converse(run, run.talk(), output, chat);
      return ended
          .map(r ->
              settle(chat.get(), output.outcome(r.exitCode(), r.timedOut(), run.timeoutSeconds())))
          .doOnNext(outcome -> finish(conversation, output, outcome, chat.get()))
          .doOnError(
              error -> conversation.end(AgentOutcome.FAILURE, String.valueOf(error.getMessage())))
          .doOnCancel(() -> conversation.end(AgentOutcome.INCOMPLETE, "cancelled"));
    });
  }

  private static Map<String, Object> settle(
      final @Nullable AgentChat chat, final Map<String, Object> outcome) {
    return chat == null ? outcome : chat.settle(outcome);
  }

  /** Records the result of the turn and ends the conversation with its status as the outcome. */
  private static void finish(
      final AgentConversationHandle conversation,
      final AgentOutput output,
      final Map<String, Object> outcome,
      final @Nullable AgentChat chat) {
    final String status = String.valueOf(outcome.get("status"));
    final Object reason = outcome.get("reason");
    output.closeTools(AgentOutcome.FAILURE.equals(status));
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", status);
    if (reason != null) {
      result.put("reason", reason);
    }
    result.put("text", outcome.get("text"));
    result.put("turn", outcome.get("turn"));
    // a turn that ended with its result line has written its RESULT already
    if (chat == null || chat.working()) {
      conversation.emit(AgentEventKind.RESULT, null, result);
    }
    conversation.end(status, reason == null ? null : String.valueOf(reason));
  }

  private static Map<String, Object> prompt(final String text) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "user");
    data.put("text", text);
    data.put("author", "prompt");
    data.put("more", false);
    return data;
  }

  private Mono<ProcessExecutionResult> converse(
      final AgentRun run,
      final AgentPreset.Interactive talk,
      final AgentOutput output,
      final AtomicReference<AgentChat> chat) {
    final InputIdentity who =
        Objects.requireNonNull(run.who(), "an agent that takes messages must run inside a node");
    final AtomicReference<AgentRequests> requests = new AtomicReference<>();
    return gateway
        .executeInteractive(
            run.spec(),
            output,
            stdin -> {
              final AgentRequests a =
                  new AgentRequests(
                      talk,
                      run.approvals(),
                      json,
                      inputs::getIfAvailable,
                      who,
                      run.message(),
                      run.preset().id(),
                      stdin,
                      run.handle());
              requests.set(a);
              final AgentChat c = new AgentChat(run, output, a, stdin, beat);
              chat.set(c);
              c.start();
            })
        .doFinally(signal -> {
          Mono.justOrEmpty(requests.get()).subscribe(AgentRequests::dispose);
          Mono.justOrEmpty(chat.get()).subscribe(AgentChat::dispose);
        });
  }
}
