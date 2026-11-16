// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.time.Duration;
import java.util.Map;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs any program in a terminal: the process is {@code yukta-cli pty-host} (the {@code launcher}
 * of the spec), which owns the pseudo-terminal and speaks {@link PtyFrames} on its standard input
 * and output. The conversation shows the terminal output and its settled screen; a message is
 * typed, an interrupt types the interrupt keys.
 */
public final class PtyAdapter implements AgentAdapter {

  private static final String PROMPT_ITEM = "prompt";

  /** The exit code of a host that was killed. */
  private static final int KILLED = -1;

  private final ProcessExecutorGateway gateway;
  private final ObjectMapper json;

  /** How often the conversation tells the engine that its node is alive. */
  private final Duration beat;

  /** How long a host gets to end after it was asked to close, before it is killed. */
  private final Duration grace;

  /**
   * Creates the adapter.
   *
   * @param gateway starts the process
   * @param json the JSON mapper
   * @param beat how often the node tells the engine that it is alive
   */
  public PtyAdapter(
      final ProcessExecutorGateway gateway, final ObjectMapper json, final Duration beat) {
    this(gateway, json, beat, Duration.ofSeconds(7));
  }

  /* default */ PtyAdapter(
      final ProcessExecutorGateway gateway,
      final ObjectMapper json,
      final Duration beat,
      final Duration grace) {
    this.gateway = gateway;
    this.json = json;
    this.beat = beat;
    this.grace = grace;
  }

  @Override
  public AgentProtocol protocol() {
    return AgentProtocol.PTY;
  }

  @Override
  public Mono<Map<String, Object>> run(final AgentRun run) {
    return Mono.defer(() -> {
      final AgentConversationHandle conversation = run.handle();
      final PtySession session = new PtySession(run, new PtyFrames(json), beat, grace);
      conversation.emit(AgentEventKind.MESSAGE, PROMPT_ITEM, session.promptEvent());
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
}
