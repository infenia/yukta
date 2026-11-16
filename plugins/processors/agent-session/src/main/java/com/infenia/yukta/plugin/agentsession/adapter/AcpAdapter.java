// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an agent that speaks the Agent Client Protocol (JSON-RPC over standard input and output):
 * starts the process, lets {@link AcpSession} hold the conversation and reads how it ended. What
 * the agent does is written to the conversation of the run, as {@link JsonlAdapter} does for a
 * harness that speaks JSON lines.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // local maps, never shared
public final class AcpAdapter implements AgentAdapter {

  /** The item of the conversation that holds the prompt. */
  private static final String PROMPT_ITEM = "prompt";

  /** The exit code reported for a process that was killed after the conversation ended. */
  private static final int KILLED = -1;

  private final ProcessExecutorGateway gateway;
  private final ObjectProvider<InputGateway> inputs;
  private final ObjectMapper json;

  /** How often an open conversation tells the engine that its node is alive. */
  private final Duration beat;

  /** How long chunks of text are gathered into one piece. */
  private final Duration gather;

  /** How long a process may live on after its standard input was closed. */
  private final Duration grace;

  /**
   * Creates the adapter.
   *
   * @param gateway starts the process
   * @param inputs the input request system, for approvals; found at use time
   * @param json the JSON mapper
   * @param beat how often an open conversation tells the engine that its node is alive
   */
  public AcpAdapter(
      final ProcessExecutorGateway gateway,
      final ObjectProvider<InputGateway> inputs,
      final ObjectMapper json,
      final Duration beat) {
    this(gateway, inputs, json, beat, Duration.ofMillis(200), Duration.ofSeconds(5));
  }

  /* default */ AcpAdapter(
      final ProcessExecutorGateway gateway,
      final ObjectProvider<InputGateway> inputs,
      final ObjectMapper json,
      final Duration beat,
      final Duration gather,
      final Duration grace) {
    this.gateway = gateway;
    this.inputs = inputs;
    this.json = json;
    this.beat = beat;
    this.gather = gather;
    this.grace = grace;
  }

  @Override
  public AgentProtocol protocol() {
    return AgentProtocol.ACP;
  }

  @Override
  public Mono<Map<String, Object>> run(final AgentRun run) {
    return Mono.defer(() -> {
      final AgentConversationHandle conversation = run.handle();
      final AcpSession session =
          new AcpSession(run, json, inputs::getIfAvailable, beat, gather, grace);
      conversation.emit(AgentEventKind.MESSAGE, PROMPT_ITEM, prompt(run.prompt()));
      conversation.state(AgentState.RUNNING, null);
      return gateway
          .executeInteractive(run.spec(), session, session::start)
          .takeUntilOther(session.killed())
          .map(r -> session.outcome(r.exitCode(), r.timedOut(), run.timeoutSeconds()))
          .switchIfEmpty(Mono.fromSupplier(() -> session.outcome(KILLED, false, 0)))
          .doOnNext(session::finish)
          .doOnError(
              error -> conversation.end(AgentOutcome.FAILURE, String.valueOf(error.getMessage())))
          .doOnCancel(() -> conversation.end(AgentOutcome.INCOMPLETE, "cancelled"))
          .doFinally(_ -> session.dispose());
    });
  }

  private static Map<String, Object> prompt(final String text) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "user");
    data.put("text", text);
    data.put("author", "prompt");
    data.put("more", false);
    return data;
  }
}
