// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * One run of a program in a terminal. It reads the frames of the host as the process writes them,
 * turns them into events of the conversation and types what the clients send. The run log gets two
 * lines, when the terminal starts and when it ends, not the bytes.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
final class PtySession implements PluginLogSink {

  private static final String STATUS = "status";
  private static final String REASON = "reason";
  private static final String TEXT = "text";
  private static final String STDERR = "stderr";
  private static final String COLS = "cols";
  private static final String ROWS = "rows";
  private static final int TEXT_MAX = 64 * 1024;
  private static final String DEFAULT_COLS = "120";
  private static final String DEFAULT_ROWS = "40";

  private final AgentRun run;
  private final PtyFrames frames;
  private final AgentPreset.Pty pty;
  private final Duration beat;
  private final Duration grace;
  private final AgentConversationHandle handle;
  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicReference<ProcessExecutorGateway.StdinWriter> target =
      new AtomicReference<>();
  private final AtomicBoolean typed = new AtomicBoolean();
  private final Disposable.Composite background = Disposables.composite();
  private final Sinks.One<Boolean> killedSink = Sinks.one();

  private AgentCommand.@Nullable End end;
  private boolean closing;
  private boolean done;
  private boolean waiting;
  private boolean due;
  private int sent;
  private int cols;
  private int rows;
  private int hostExit = KILLED_CODE;
  private String settled = "";

  private static final int KILLED_CODE = Integer.MIN_VALUE;

  /* default */ PtySession(
      final AgentRun run, final PtyFrames frames, final Duration beat, final Duration grace) {
    this.run = run;
    this.frames = frames;
    this.pty = java.util.Objects.requireNonNull(run.preset().pty(), "a pty preset has a pty block");
    this.beat = beat;
    this.grace = grace;
    this.handle = run.handle();
    this.cols = launcherNumber(run.spec().launcher(), "--cols", DEFAULT_COLS);
    this.rows = launcherNumber(run.spec().launcher(), "--rows", DEFAULT_ROWS);
  }

  private static int launcherNumber(
      final List<String> launcher, final String flag, final String fallback) {
    final int at = launcher.indexOf(flag);
    try {
      return Integer.parseInt(
          at >= 0 && at + 1 < launcher.size() ? launcher.get(at + 1) : fallback);
    } catch (NumberFormatException e) {
      return Integer.parseInt(fallback);
    }
  }

  /* default */ Map<String, Object> promptEvent() {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "user");
    data.put(TEXT, run.prompt());
    data.put("author", "prompt");
    data.put("more", false);
    return data;
  }

  /* default */ Mono<Boolean> killed() {
    return killedSink.asMono();
  }

  /**
   * Starts the conversation once the host runs.
   *
   * @param stdin where the frames go
   */
  /* default */ void start(final ProcessExecutorGateway.StdinWriter stdin) {
    target.set(stdin);
    run.log().line("agent", "terminal started: " + run.spec().command().get(0));
    background.add(handle.commands().subscribe(this::command));
    background.add(Flux.interval(beat).subscribe(_ -> run.heartbeat().run()));
    if (!run.promptInArgs() && !run.prompt().isEmpty()) {
      background.add(
          Mono.delay(Duration.ofMillis(Math.max(0, pty.readyDelayMs())))
              .subscribe(_ -> delayPassed()));
    }
  }

  // ---- lines of the host

  @Override
  public void line(final String stream, final String line, final @Nullable LogLevel level) {
    if (STDERR.equals(stream)) {
      notice(level == LogLevel.ERROR ? "ERROR" : "WARN", line);
      return;
    }
    final PtyFrames.Host frame = frames.parse(line);
    if (frame == null) {
      notice("WARN", "the terminal host sent something that is not a frame");
      return;
    }
    lock.lock();
    try {
      switch (frame.kind()) {
        case "out" -> output(frame);
        case "screen" -> screen(frame);
        default -> hostExit = frame.code();
      }
    } finally {
      lock.unlock();
    }
  }

  private void notice(final String level, final String text) {
    final Map<String, Object> notice = new LinkedHashMap<>();
    notice.put("level", level);
    notice.put(TEXT, text);
    handle.emit(AgentEventKind.NOTICE, null, notice);
  }

  private void output(final PtyFrames.Host frame) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("data", Base64.getEncoder().encodeToString(frame.data()));
    data.put(COLS, cols);
    data.put(ROWS, rows);
    handle.emit(AgentEventKind.TERMINAL, null, data);
  }

  private void screen(final PtyFrames.Host frame) {
    settled = frame.text();
    cols = frame.cols() > 0 ? frame.cols() : cols;
    rows = frame.rows() > 0 ? frame.rows() : rows;
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("screen", frame.text());
    if (!frame.styled().isEmpty()) {
      data.put("styled", frame.styled());
    }
    data.put(COLS, cols);
    data.put(ROWS, rows);
    data.put("cursorRow", frame.cursorRow());
    data.put("cursorCol", frame.cursorCol());
    data.put("settled", true);
    handle.emit(AgentEventKind.TERMINAL, null, data);
    attention(frame.text());
    // never into a screen that wants attention: a trust dialog takes Enter as its answer
    if (!waiting && (due || matches(pty.readyPattern(), frame.text()))) {
      typePrompt();
    }
    if (matches(pty.donePattern(), frame.text())) {
      done = true;
      closeHost();
    }
  }

  private void attention(final String text) {
    final boolean wants = matches(pty.attentionPattern(), text);
    if (wants && !waiting) {
      waiting = true;
      handle.state(AgentState.WAITING, "the terminal wants attention");
    } else if (!wants && waiting) {
      waiting = false;
      handle.state(AgentState.RUNNING, null);
    }
  }

  private static boolean matches(final @Nullable Pattern pattern, final String text) {
    return pattern != null && pattern.matcher(text).find();
  }

  // ---- typing

  /** The ready delay has passed: type the prompt unless the screen wants attention first. */
  private void delayPassed() {
    lock.lock();
    try {
      due = true;
      if (!waiting) {
        typePrompt();
      }
    } finally {
      lock.unlock();
    }
  }

  private void typePrompt() {
    if (typed.compareAndSet(false, true)) {
      final String text = pty.type().replace("{prompt}", run.prompt());
      if (pty.submitDelayMs() > 0) {
        typeAndSubmit(text);
      } else {
        type(text);
      }
    }
  }

  /** Types text and the submit keys, these apart after the preset's pause when it has one. */
  private void typeAndSubmit(final String text) {
    if (pty.submitDelayMs() <= 0) {
      type(text + pty.submit());
      return;
    }
    type(text);
    background.add(
        Mono.delay(Duration.ofMillis(pty.submitDelayMs())).subscribe(_ -> type(pty.submit())));
  }

  private void type(final String text) {
    write(frames.keys(text));
  }

  private void write(final String frame) {
    // set by start() before anything can type or close
    target.get().write(frame);
  }

  // ---- commands of clients

  private void command(final AgentCommand command) {
    lock.lock();
    try {
      switch (command) {
        case AgentCommand.Message message -> message(message);
        case AgentCommand.Interrupt _ -> type(pty.interruptKeys());
        case AgentCommand.End finish -> {
          end = finish;
          closeHost();
        }
        case AgentCommand.TerminalInput input -> write(frames.keys(input.data()));
        case AgentCommand.TerminalResize size -> {
          cols = size.cols();
          rows = size.rows();
          write(frames.resize(size.cols(), size.rows()));
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private void message(final AgentCommand.Message message) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "user");
    data.put(TEXT, message.text());
    data.put("author", message.actor());
    data.put("more", false);
    sent++;
    handle.emit(AgentEventKind.MESSAGE, "u" + sent, data);
    if (message.interrupt()) {
      type(pty.interruptKeys());
    }
    typeAndSubmit(message.text());
  }

  /** Asks the host to end the program; one that stays is killed after the grace period. */
  private void closeHost() {
    if (closing) {
      return;
    }
    closing = true;
    write(frames.close());
    background.add(Mono.delay(grace).subscribe(_ -> killedSink.tryEmitValue(true)));
  }

  // ---- the end

  /**
   * What the node sends on once the process has ended.
   *
   * @param exitCode the exit code of the process, or -1 when it was killed
   * @param timedOut whether the process was stopped at its timeout
   * @param timeoutSeconds the timeout, for the message
   * @return the outcome map
   */
  /* default */ Map<String, Object> outcome(
      final int exitCode, final boolean timedOut, final long timeoutSeconds) {
    lock.lock();
    try {
      final int code = hostExit == KILLED_CODE ? exitCode : hostExit;
      final Map<String, Object> out = new LinkedHashMap<>();
      settle(out, code, timedOut, timeoutSeconds);
      out.put(TEXT, lastScreen());
      out.put("exitCode", code);
      out.put("sessionId", "");
      out.put("turn", 1);
      return out;
    } finally {
      lock.unlock();
    }
  }

  private void settle(
      final Map<String, Object> out, final int code, final boolean timedOut, final long seconds) {
    if (timedOut) {
      out.put(STATUS, AgentOutcome.FAILURE);
      out.put(REASON, "timed out after " + seconds + "s");
    } else if (end != null || done) {
      // we ended it: the host stopped the program, so its exit code says nothing
      out.put(STATUS, end != null && end.outcome() != null ? end.outcome() : AgentOutcome.SUCCESS);
      if (end != null && end.reason() != null) {
        out.put(REASON, end.reason());
      }
    } else if (code == 0) {
      out.put(STATUS, AgentOutcome.SUCCESS);
    } else {
      out.put(STATUS, AgentOutcome.FAILURE);
      out.put(REASON, "the program exited with code " + code);
    }
  }

  /** The last screen without its trailing blank lines, cut to the size a result may have. */
  private String lastScreen() {
    final String trimmed = settled.stripTrailing();
    final byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
    return bytes.length <= TEXT_MAX
        ? trimmed
        : new String(bytes, 0, TEXT_MAX, StandardCharsets.UTF_8);
  }

  /**
   * Records the end: the result of the run and the outcome of the conversation.
   *
   * @param outcome what {@link #outcome} returned
   */
  /* default */ void finish(final Map<String, Object> outcome) {
    final String status = String.valueOf(outcome.get(STATUS));
    final Object reason = outcome.get(REASON);
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put(STATUS, status);
    if (reason != null) {
      result.put(REASON, reason);
    }
    result.put(TEXT, outcome.get(TEXT));
    result.put("turn", 1);
    handle.emit(AgentEventKind.RESULT, null, result);
    run.log().line("agent", "terminal ended: " + status);
    handle.end(status, reason == null ? null : String.valueOf(reason));
  }

  /* default */ void dispose() {
    background.dispose();
  }
}
