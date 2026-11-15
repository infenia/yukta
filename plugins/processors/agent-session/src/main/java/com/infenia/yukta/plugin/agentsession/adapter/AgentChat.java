// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The turns of one agent process that takes its messages on standard input: starts a turn for the
 * prompt and for each message, queues what the agent cannot take during a turn, stops a turn when
 * asked, and decides what happens when a turn ends: the next queued message, standard input closed
 * (a task), or waiting for a message (a conversation) until it is ended or idle for too long.
 *
 * <p>All state changes go through this object's lock: commands arrive on a thread of the
 * conversation, results on the thread that reads the process output.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap"}) // local maps, never shared
final class AgentChat {

  private static final String STATUS = "status";
  private static final String TURN = "turn";
  private static final String REASON = "reason";

  private final AgentRun run;
  private final AgentOutput output;
  private final AgentRequests requests;
  private final ProcessExecutorGateway.StdinWriter stdin;
  private final Duration beat;
  private final AgentConversationHandle handle;
  private final ReentrantLock lock = new ReentrantLock();
  private final Mailbox mailbox = new Mailbox();
  private final Disposable.Composite background = Disposables.composite();

  /** The harness takes a message during a turn. */
  private final boolean steer;

  /** The agent works on a turn. */
  private boolean busy;

  /** The turn that runs was asked to stop. */
  private boolean interrupted;

  /** The last turn ended because it was stopped. */
  private boolean lastStopped;

  /** The conversation was idle for too long. */
  private boolean idleOut;

  /** What ended the conversation, when someone did. */
  private AgentCommand.@Nullable End end;

  /** The outcome of the last turn that ended. */
  private Map<String, Object> last = Map.of();

  private int sent;
  private @Nullable Disposable idle;

  /* default */ AgentChat(
      final AgentRun run,
      final AgentOutput output,
      final AgentRequests requests,
      final ProcessExecutorGateway.StdinWriter stdin,
      final Duration beat) {
    this.run = run;
    this.beat = beat;
    this.output = output;
    this.requests = requests;
    this.stdin = stdin;
    this.handle = run.handle();
    this.steer = Objects.requireNonNull(run.talk()).steer();
  }

  /** Starts the first turn and listens for commands. */
  /* default */ void start() {
    lock.lock();
    try {
      startLocked();
    } finally {
      lock.unlock();
    }
  }

  private void startLocked() {
    output.converse(requests::handle, this::turnEnded);
    busy = true;
    requests.say(run.prompt());
    background.add(handle.commands().subscribe(this::command));
    if (run.chat()) {
      background.add(Flux.interval(beat).subscribe(_ -> run.heartbeat().run()));
    }
  }

  /**
   * Whether the agent was in the middle of a turn.
   *
   * @return true until the result of the turn arrives
   */
  /* default */ boolean working() {
    lock.lock();
    try {
      return busy;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Turns what the process reported into what the conversation ended with: a turn the user stopped
   * is {@code incomplete}, not a failure; an end with an outcome and a conversation that was idle
   * for too long say so.
   *
   * @param base what the output rules made of the process
   * @return the outcome of the node
   */
  /* default */ Map<String, Object> settle(final Map<String, Object> base) {
    lock.lock();
    try {
      return settleLocked(base);
    } finally {
      lock.unlock();
    }
  }

  private Map<String, Object> settleLocked(final Map<String, Object> base) {
    final Map<String, Object> outcome = lastStopped ? new LinkedHashMap<>(last) : base;
    outcome.put("exitCode", base.get("exitCode"));
    outcome.put(TURN, base.get(TURN));
    if (end != null && end.outcome() != null) {
      outcome.put(STATUS, end.outcome());
      outcome.remove(REASON);
    }
    if (end != null && end.reason() != null) {
      outcome.put(REASON, end.reason());
    } else if (idleOut) {
      outcome.put(REASON, "idle");
    }
    return outcome;
  }

  private void command(final AgentCommand command) {
    lock.lock();
    try {
      route(command);
    } finally {
      lock.unlock();
    }
  }

  private void route(final AgentCommand command) {
    switch (command) {
      case AgentCommand.Message message -> message(message);
      case AgentCommand.Interrupt _ -> interrupt();
      case AgentCommand.End finish -> finish(finish);
      default -> {
        // terminal input belongs to the terminal tier
      }
    }
  }

  private void message(final AgentCommand.Message message) {
    final boolean wait = busy && (message.interrupt() || !steer);
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "user");
    data.put("text", message.text());
    data.put("author", message.actor());
    data.put("more", false);
    if (wait) {
      data.put("queued", true);
    }
    sent++;
    handle.emit(AgentEventKind.MESSAGE, "u" + sent, data);
    if (!busy) {
      begin(message.text());
      return;
    }
    if (message.interrupt()) {
      interrupt();
    }
    if (wait) {
      handle.queued(mailbox.add(message.text(), message.actor()));
    } else {
      requests.say(message.text());
    }
  }

  private void interrupt() {
    if (busy && requests.interrupt()) {
      interrupted = true;
    }
  }

  private void finish(final AgentCommand.End finish) {
    end = finish;
    cancelIdle();
    if (busy) {
      interrupt();
    }
    stdin.close();
  }

  private void begin(final String text) {
    cancelIdle();
    output.nextTurn();
    busy = true;
    interrupted = false;
    lastStopped = false;
    handle.state(AgentState.RUNNING, null);
    requests.say(text);
  }

  private void turnEnded() {
    lock.lock();
    try {
      turnEndedLocked();
    } finally {
      lock.unlock();
    }
  }

  private void turnEndedLocked() {
    busy = false;
    final Map<String, Object> outcome = output.outcome(0, false, 0);
    lastStopped = interrupted && AgentOutcome.FAILURE.equals(outcome.get(STATUS));
    if (lastStopped) {
      outcome.put(STATUS, AgentOutcome.INCOMPLETE);
      outcome.put(REASON, "interrupted");
    }
    last = outcome;
    output.closeTools(AgentOutcome.FAILURE.equals(outcome.get(STATUS)));
    handle.emit(AgentEventKind.RESULT, null, result(outcome));
    final Mailbox.Waiting next = end == null ? mailbox.next() : null;
    if (next != null) {
      handle.queued(mailbox.size());
      begin(next.text());
    } else if (end == null && run.chat()) {
      handle.state(AgentState.IDLE, null);
      idle = Mono.delay(run.idleTimeout()).subscribe(_ -> idled());
    } else {
      stdin.close();
    }
  }

  // ponytail: a message that arrives in the instant the timer fires can lose to the timer; a
  // fixed epoch check would close it, add if idle timeouts get short
  private void idled() {
    lock.lock();
    try {
      idleOut = true;
      stdin.close();
    } finally {
      lock.unlock();
    }
  }

  private void cancelIdle() {
    if (idle != null) {
      idle.dispose();
    }
  }

  private static Map<String, Object> result(final Map<String, Object> outcome) {
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put(STATUS, outcome.get(STATUS));
    if (outcome.get(REASON) != null) {
      result.put(REASON, outcome.get(REASON));
    }
    result.put("text", outcome.get("text"));
    result.put(TURN, outcome.get(TURN));
    return result;
  }

  /* default */ void dispose() {
    background.dispose();
    cancelIdle();
  }
}
