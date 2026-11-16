// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentOutcome;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One run of an agent over the Agent Client Protocol: the handshake, the turns, the requests for
 * permission, the updates that become the events of the conversation, and the end. It reads the
 * lines of the process as its log sink; commands of clients and answers of the agent arrive on
 * different threads, so every change of state goes through one lock.
 *
 * <p>A message sent during a turn waits for the end of the turn (ACP cannot take one earlier), and
 * {@code interrupt: true} cancels the turn first.
 */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap",
  "PMD.GodClass",
  "PMD.CouplingBetweenObjects",
  "PMD.CyclomaticComplexity",
  "PMD.ExcessiveImports",
  "PMD.NPathComplexity",
  "PMD.AvoidStringBufferField"
})
final class AcpSession implements PluginLogSink, AcpWire.Listener {

  private static final String STATUS = "status";
  private static final String REASON = "reason";
  private static final String TEXT = "text";
  private static final String STDERR = "stderr";
  private static final String AGENT = "agent";
  private static final String OUTCOME_KEY = "outcome";
  private static final String TURN = "turn";
  private static final String SESSION_ID = "sessionId";
  private static final String TITLE = "title";
  private static final String KIND = "kind";
  private static final String PERMISSION_METHOD = "session/request_permission";
  private static final String UPDATE_METHOD = "session/update";
  private static final String COMPLETED = "completed";
  private static final String FAILED_STATUS = "failed";

  /** The version of the protocol Yukta speaks. */
  private static final int VERSION = 1;

  /** The most agent text of a turn kept for the payload. */
  private static final int TEXT_MAX = 64 * 1024;

  /** JSON-RPC: the method does not exist. */
  private static final int METHOD_NOT_FOUND = -32_601;

  private final AgentRun run;
  private final ObjectMapper json;
  private final Duration beat;
  private final Duration gather;
  private final Duration grace;
  private final AgentConversationHandle handle;
  private final LogMirror mirror;
  private final AtomicReference<ProcessExecutorGateway.StdinWriter> target =
      new AtomicReference<>();
  private final ProcessExecutorGateway.StdinWriter pipe;
  private final AcpWire wire;
  private final AgentRequests requests;
  private final ReentrantLock lock = new ReentrantLock();
  private final Mailbox mailbox = new Mailbox();
  private final Disposable.Composite background = Disposables.composite();
  private final Sinks.One<Boolean> killedSink = Sinks.one();

  /** The permission requests the agent waits for, by the id of the request in the conversation. */
  private final Map<String, Permission> permissions = new ConcurrentHashMap<>();

  /** The items of the conversation of the tools that run, by the id the agent gave. */
  private final Map<String, String> tools = new ConcurrentHashMap<>();

  /** The tools that have not ended. */
  private final Deque<String> runningTools = new ArrayDeque<>();

  private final AtomicInteger items = new AtomicInteger();
  private final StringBuilder piece = new StringBuilder();
  private final StringBuilder turnText = new StringBuilder();

  /** The agent takes a message, or the first prompt is on its way. */
  private boolean busy = true;

  /** The conversation was idle for too long. */
  private boolean idleOut;

  /** The agent announced it can load a session. */
  private boolean loadSession;

  /** The agent replays the history of a session it loads; none of it is new. */
  private boolean replaying;

  /** The standard input was closed. */
  private boolean closing;

  /** The outcome of the last turn that ended. */
  private Map<String, Object> last = Map.of();

  private int turns = 1;
  private int sent;
  private String sessionId = "";
  private String pieceRole = "";
  private AgentCommand.@Nullable End end;
  private @Nullable Disposable idle;
  private @Nullable Disposable gathering;

  /** What an open permission request needs to be answered. */
  private record Permission(JsonNode id, JsonNode options) {}

  /* default */ AcpSession(
      final AgentRun run,
      final ObjectMapper json,
      final Supplier<@Nullable InputGateway> inputs,
      final Duration beat,
      final Duration gather,
      final Duration grace) {
    this.run = run;
    this.json = json;
    this.beat = beat;
    this.gather = gather;
    this.grace = grace;
    this.handle = run.handle();
    this.mirror = new LogMirror(run.log());
    this.pipe = newPipe();
    this.wire = new AcpWire(pipe, json);
    this.requests =
        new AgentRequests(
            new Options(),
            run.approvals(),
            json,
            inputs,
            run.who(),
            run.message(),
            run.preset().id(),
            pipe,
            handle);
  }

  private ProcessExecutorGateway.StdinWriter newPipe() {
    return new ProcessExecutorGateway.StdinWriter() {
      @Override
      public void write(final String line) {
        target.get().write(line);
      }

      @Override
      public void close() {
        target.get().close();
      }
    };
  }

  /**
   * Emits when the process outlived the end of the conversation by too long.
   *
   * @return completes when the process should be killed
   */
  /* default */ Mono<Boolean> killed() {
    return killedSink.asMono();
  }

  /**
   * Starts the conversation once the process runs.
   *
   * @param stdin where the requests go
   */
  /* default */ void start(final ProcessExecutorGateway.StdinWriter stdin) {
    target.set(stdin);
    background.add(handle.commands().subscribe(this::command));
    if (run.chat()) {
      background.add(Flux.interval(beat).subscribe(_ -> run.heartbeat().run()));
    }
    background.add(handshake().doOnError(this::failed).onErrorComplete().subscribe());
  }

  private Mono<Void> handshake() {
    final Map<String, Object> client = new LinkedHashMap<>();
    client.put("fs", Map.of("readTextFile", false, "writeTextFile", false));
    client.put("terminal", false);
    return wire.request(
            "initialize", Map.of("protocolVersion", VERSION, "clientCapabilities", client))
        .flatMap(this::initialized)
        .then(Mono.defer(this::authenticate))
        .then(Mono.defer(this::open))
        .doOnNext(id -> {
          sessionId = id;
          prompt(run.prompt());
        })
        .then();
  }

  private Mono<Void> initialized(final JsonNode answer) {
    final int version = answer.path("protocolVersion").asInt(0);
    if (version != VERSION) {
      return Mono.error(
          new IllegalStateException(
              "the agent speaks version "
                  + version
                  + " of the Agent Client Protocol; Yukta speaks version "
                  + VERSION));
    }
    loadSession = answer.path("agentCapabilities").path("loadSession").asBoolean(false);
    return Mono.empty();
  }

  private Mono<Void> authenticate() {
    final String method = run.preset().authMethod();
    return method == null
        ? Mono.empty()
        : wire.request("authenticate", Map.of("methodId", method)).then();
  }

  private Mono<String> open() {
    final String token = run.resume();
    final Map<String, Object> params = new LinkedHashMap<>();
    params.put("cwd", cwd());
    params.put("mcpServers", List.of());
    if (token != null && !token.isBlank() && loadSession) {
      params.put(SESSION_ID, token);
      return Mono.defer(
              () -> {
                replaying = true;
                return wire.request("session/load", params);
              })
          .doOnNext(_ -> replaying = false)
          .thenReturn(token);
    }
    return wire.request("session/new", params)
        .map(answer -> answer.path(SESSION_ID).asString(""))
        .filter(id -> !id.isBlank())
        .switchIfEmpty(Mono.error(new IllegalStateException("the agent gave no session id")));
  }

  private String cwd() {
    final String dir = run.spec().workingDir();
    return Path.of(dir == null ? "" : dir).toAbsolutePath().toString();
  }

  // ---- lines of the process

  @Override
  public void line(final String stream, final String line, final @Nullable LogLevel level) {
    if (STDERR.equals(stream)) {
      mirror.passThrough(stream, line, level);
      final Map<String, Object> notice = new LinkedHashMap<>();
      notice.put("level", level == LogLevel.ERROR ? "ERROR" : "WARN");
      notice.put(TEXT, line);
      handle.emit(AgentEventKind.NOTICE, null, notice);
    } else if (!wire.accept(line, this)) {
      mirror.passThrough(stream, line, level);
    }
  }

  @Override
  public void request(final JsonNode id, final String method, final JsonNode params) {
    if (PERMISSION_METHOD.equals(method)) {
      permission(id, params);
    } else {
      wire.fail(id, METHOD_NOT_FOUND, "Method not found: " + method);
    }
  }

  @Override
  public void notification(final String method, final JsonNode params) {
    if (UPDATE_METHOD.equals(method)) {
      update(params.path("update"));
    }
  }

  // ---- what the agent reports

  private void update(final JsonNode update) {
    lock.lock();
    try {
      if (!replaying) {
        updateLocked(update);
      }
    } finally {
      lock.unlock();
    }
  }

  private void updateLocked(final JsonNode update) {
    switch (update.path("sessionUpdate").asString("")) {
      case "agent_message_chunk" -> chunk(AGENT, update.path("content").path(TEXT).asString(""));
      case "agent_thought_chunk" ->
          chunk("thought", update.path("content").path(TEXT).asString(""));
      case "tool_call", "tool_call_update" -> tool(update);
      case "plan" -> plan(update);
      default -> {
        // the echo of the prompt, commands, modes and usage are not part of the conversation
      }
    }
  }

  private void chunk(final String role, final String text) {
    if (text.isEmpty()) {
      return;
    }
    if (!role.equals(pieceRole)) {
      flush();
    }
    pieceRole = role;
    piece.append(text);
    if (gathering == null) {
      gathering = Mono.delay(gather).subscribe(_ -> gathered());
    }
  }

  private void gathered() {
    lock.lock();
    try {
      flush();
    } finally {
      lock.unlock();
    }
  }

  /** Writes the text gathered so far as one message. */
  @SuppressWarnings("PMD.NullAssignment") // the timer is done
  private void flush() {
    if (gathering != null) {
      gathering.dispose();
      gathering = null;
    }
    if (piece.length() == 0) {
      return;
    }
    final String text = piece.toString();
    piece.setLength(0);
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", pieceRole);
    data.put(TEXT, text);
    data.put("more", false);
    handle.emit(AgentEventKind.MESSAGE, "m" + items.incrementAndGet(), data);
    mirror.agent(text, null);
    if (AGENT.equals(pieceRole)) {
      turnText.append(text);
      if (turnText.length() > TEXT_MAX) {
        turnText.setLength(TEXT_MAX);
      }
    }
  }

  private void tool(final JsonNode update) {
    flush();
    final String callId = update.path("toolCallId").asString("");
    final boolean known = tools.containsKey(callId);
    final String item = tools.computeIfAbsent(callId, _ -> "t" + items.incrementAndGet());
    final Map<String, Object> data = new LinkedHashMap<>();
    if (update.path(TITLE).isString()) {
      data.put(TITLE, update.path(TITLE).asString());
      if (!known) {
        mirror.agent(update.path(TITLE).asString(), null);
      }
    }
    if (update.path(KIND).isString()) {
      data.put(KIND, update.path(KIND).asString());
    }
    if (!update.path("rawInput").isMissingNode()) {
      data.put("input", json.convertValue(update.path("rawInput"), Object.class));
    }
    if (!update.path("rawOutput").isMissingNode()) {
      data.put("output", json.convertValue(update.path("rawOutput"), Object.class));
    }
    final String status = update.path(STATUS).asString("");
    data.put(STATUS, COMPLETED.equals(status) || FAILED_STATUS.equals(status) ? status : "running");
    handle.emit(AgentEventKind.TOOL, item, data);
    if (COMPLETED.equals(status) || FAILED_STATUS.equals(status)) {
      runningTools.remove(item);
    } else if (!runningTools.contains(item)) {
      runningTools.add(item);
    }
  }

  private void plan(final JsonNode update) {
    flush();
    handle.emit(
        AgentEventKind.PLAN,
        null,
        Map.of("entries", json.convertValue(update.path("entries"), Object.class)));
  }

  // ---- permission

  /** Chooses the option of an answer by its kind: the agent's option ids are its own. */
  private final class Options implements AgentRequests.Replies {

    @Override
    public String allow(final AgentOutput.Request request) {
      return answer(request, "allow_once", "allow_always");
    }

    @Override
    public String deny(final AgentOutput.Request request, final String reason) {
      return answer(request, "reject_once", "reject_always");
    }

    private String answer(final AgentOutput.Request request, final String... kinds) {
      final Permission open =
          Objects.requireNonNull(permissions.remove(request.id()), "no open permission request");
      for (final String kind : kinds) {
        for (final JsonNode option : open.options()) {
          if (kind.equals(option.path(KIND).asString(""))) {
            return wire.replyLine(
                open.id(),
                Map.of(
                    OUTCOME_KEY,
                    Map.of(
                        OUTCOME_KEY, "selected", "optionId", option.path("optionId").asString())));
          }
        }
      }
      return wire.replyLine(open.id(), Map.of(OUTCOME_KEY, Map.of(OUTCOME_KEY, "cancelled")));
    }
  }

  private void permission(final JsonNode id, final JsonNode params) {
    final JsonNode call = params.path("toolCall");
    final String key = call.path("toolCallId").asString(id.asString());
    permissions.put(key, new Permission(id, params.path("options")));
    requests.handle(
        new AgentOutput.Request(
            key,
            call.path(TITLE).asString(call.path(KIND).asString("a tool")),
            call.path("rawInput"),
            call.path(KIND).asString("")));
  }

  /** Answers every open request {@code cancelled}: the turn they belong to was stopped. */
  private void cancelPermissions() {
    requests.dispose();
    permissions
        .values()
        .forEach(
            open -> wire.reply(open.id(), Map.of(OUTCOME_KEY, Map.of(OUTCOME_KEY, "cancelled"))));
    permissions.clear();
  }

  // ---- commands of clients

  private void command(final AgentCommand command) {
    lock.lock();
    try {
      switch (command) {
        case AgentCommand.Message message -> message(message);
        case AgentCommand.Interrupt _ -> interrupt();
        case AgentCommand.End finish -> ending(finish);
        default -> {
          // terminal input belongs to the terminal tier
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
    if (busy) {
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
    handle.queued(mailbox.add(message.text(), message.actor()));
  }

  private void interrupt() {
    if (busy && !sessionId.isEmpty()) {
      wire.notify("session/cancel", Map.of(SESSION_ID, sessionId));
      cancelPermissions();
    }
  }

  private void ending(final AgentCommand.End finish) {
    end = finish;
    cancelIdle();
    if (busy) {
      interrupt();
      background.add(Mono.delay(grace).subscribe(_ -> closeNow()));
    } else {
      closeStdin();
    }
  }

  private void closeNow() {
    lock.lock();
    try {
      closeStdin();
    } finally {
      lock.unlock();
    }
  }

  /** Closes standard input, which ends a well-behaved agent; one that stays is killed. */
  private void closeStdin() {
    if (closing) {
      return;
    }
    closing = true;
    pipe.close();
    background.add(Mono.delay(grace).subscribe(_ -> killedSink.tryEmitValue(true)));
  }

  // ---- turns

  private void begin(final String text) {
    cancelIdle();
    turns++;
    turnText.setLength(0);
    handle.state(AgentState.RUNNING, null);
    prompt(text);
  }

  private void prompt(final String text) {
    busy = true;
    background.add(
        wire.request(
                "session/prompt",
                Map.of(SESSION_ID, sessionId, "prompt", List.of(Map.of("type", TEXT, TEXT, text))))
            .subscribe(this::turnEnded, this::failed));
  }

  private void turnEnded(final JsonNode answer) {
    final String stop = answer.path("stopReason").asString("");
    final Map<String, Object> outcome = new LinkedHashMap<>();
    switch (stop) {
      case "end_turn" -> outcome.put(STATUS, AgentOutcome.SUCCESS);
      case "refusal" -> {
        outcome.put(STATUS, AgentOutcome.FAILURE);
        outcome.put(REASON, "the agent refused");
      }
      case "cancelled" -> {
        outcome.put(STATUS, AgentOutcome.INCOMPLETE);
        outcome.put(REASON, "interrupted");
      }
      default -> {
        outcome.put(STATUS, AgentOutcome.INCOMPLETE);
        outcome.put(REASON, stop);
      }
    }
    endTurn(outcome, false);
  }

  private void failed(final Throwable error) {
    final Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put(STATUS, AgentOutcome.FAILURE);
    outcome.put(REASON, String.valueOf(error.getMessage()));
    endTurn(outcome, true);
  }

  private void endTurn(final Map<String, Object> outcome, final boolean fatal) {
    lock.lock();
    try {
      endTurnLocked(outcome, fatal);
    } finally {
      lock.unlock();
    }
  }

  private void endTurnLocked(final Map<String, Object> outcome, final boolean fatal) {
    flush();
    busy = false;
    cancelPermissions();
    outcome.put(TEXT, turnText.toString());
    outcome.put(TURN, turns);
    last = outcome;
    closeTools(AgentOutcome.FAILURE.equals(outcome.get(STATUS)));
    handle.emit(AgentEventKind.RESULT, null, outcome);
    final Mailbox.Waiting next = fatal || end != null ? null : mailbox.next();
    if (next != null) {
      handle.queued(mailbox.size());
      begin(next.text());
    } else if (!fatal && end == null && run.chat()) {
      handle.state(AgentState.IDLE, null);
      idle = Mono.delay(run.idleTimeout()).subscribe(_ -> idled());
    } else {
      closeStdin();
    }
  }

  private void closeTools(final boolean failedTurn) {
    String item = runningTools.poll();
    while (item != null) {
      handle.emit(
          AgentEventKind.TOOL, item, Map.of(STATUS, failedTurn ? FAILED_STATUS : COMPLETED));
      item = runningTools.poll();
    }
  }

  private void idled() {
    lock.lock();
    try {
      idleOut = true;
      closeStdin();
    } finally {
      lock.unlock();
    }
  }

  private void cancelIdle() {
    if (idle != null) {
      idle.dispose();
    }
  }

  // ---- the end

  /**
   * What the node sends on once the process has ended.
   *
   * @param exitCode the exit code, or -1 when the process was killed
   * @param timedOut whether the process was stopped at its timeout
   * @param timeoutSeconds the timeout, for the message
   * @return the outcome map
   */
  /* default */ Map<String, Object> outcome(
      final int exitCode, final boolean timedOut, final long timeoutSeconds) {
    lock.lock();
    try {
      return outcomeLocked(exitCode, timedOut, timeoutSeconds);
    } finally {
      lock.unlock();
    }
  }

  private Map<String, Object> outcomeLocked(
      final int exitCode, final boolean timedOut, final long timeoutSeconds) {
    flush();
    final Map<String, Object> out = new LinkedHashMap<>();
    if (timedOut) {
      fail(out, "timed out after " + timeoutSeconds + "s");
    } else if (busy) {
      fail(out, "the agent gave no result (exit code " + exitCode + ")");
    } else {
      out.putAll(last);
    }
    out.put("exitCode", exitCode);
    out.put(SESSION_ID, sessionId);
    out.put(TURN, turns);
    if (end != null && end.outcome() != null) {
      out.put(STATUS, end.outcome());
      out.remove(REASON);
    }
    if (end != null && end.reason() != null) {
      out.put(REASON, end.reason());
    } else if (idleOut) {
      out.put(REASON, "idle");
    }
    return out;
  }

  private void fail(final Map<String, Object> out, final String reason) {
    out.put(STATUS, AgentOutcome.FAILURE);
    out.put(REASON, reason);
    out.put(TEXT, turnText.toString());
  }

  /**
   * Records the end: the result of a turn that was cut off, and the outcome of the conversation.
   *
   * @param outcome what {@link #outcome} returned
   */
  /* default */ void finish(final Map<String, Object> outcome) {
    final String status = String.valueOf(outcome.get(STATUS));
    final Object reason = outcome.get(REASON);
    lock.lock();
    try {
      closeTools(AgentOutcome.FAILURE.equals(status));
      if (busy) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put(STATUS, status);
        result.put(REASON, reason);
        result.put(TEXT, outcome.get(TEXT));
        result.put(TURN, outcome.get(TURN));
        handle.emit(AgentEventKind.RESULT, null, result);
      }
    } finally {
      lock.unlock();
    }
    handle.end(status, reason == null ? null : String.valueOf(reason));
  }

  /* default */ void dispose() {
    background.dispose();
    cancelIdle();
    requests.dispose();
  }
}
