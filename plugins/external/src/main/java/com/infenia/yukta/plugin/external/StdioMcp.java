// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.mcp.McpCallResult;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway.StdinWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * An MCP server the plugin starts as a long-lived process and talks to over stdio: one JSON-RPC
 * message per line. The process gets the clean environment of an installed command ({@code PATH}
 * plus {@code env}); the MCP Java SDK's stdio transport would pass the whole server environment. It
 * starts on first use and again after it exits; calls run concurrently, matched by id.
 */
@Slf4j
@SuppressWarnings({
  "PMD.AvoidSynchronizedAtMethodLevel", // one lock guards the process and its session
  "PMD.NullAssignment", // null: no process running
  "PMD.UseConcurrentHashMap", // a request, local to one call
  "PMD.CompareObjectsWithEquals", // the same session, not an equal one
  "PMD.TooManyMethods" // a JSON-RPC client: start, handshake, send, reply, exit
})
public final class StdioMcp implements McpConnection {

  private static final String PROTOCOL_VERSION = "2025-06-18";
  private static final String JSONRPC = "jsonrpc";
  private static final String ID = "id";
  private static final String METHOD = "method";
  private static final String RESULT = "result";
  private static final String LIST = "tools/list";
  private static final String PLUGIN = "Plugin ";

  /** How long the process may live: as long as the server. */
  private static final long FOREVER_SECONDS = Duration.ofDays(3650).toSeconds();

  /** Most pages of {@code tools/list} followed. */
  private static final int MAX_PAGES = 50;

  private final String type;
  private final List<String> argv;
  private final Map<String, String> env;
  private final Path dir;
  private final Duration timeout;
  private final ProcessExecutorGateway gateway;
  private final JsonMapper json;
  private final Runnable onClose;
  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Sinks.One<Map<String, Object>>> pending = new ConcurrentHashMap<>();

  private @Nullable Mono<StdinWriter> session;
  private @Nullable Disposable process;
  private boolean closed;

  /**
   * Creates the connection; nothing starts until the first call.
   *
   * @param type the plugin type, for logs and errors
   * @param argv the server command
   * @param env variables besides {@code PATH}
   * @param dir the working directory
   * @param timeout the longest one request may take
   * @param gateway starts the process
   * @param json reads and writes the messages
   * @param onClose run after the process was told to stop, such as removing its container
   */
  public StdioMcp(
      final String type,
      final List<String> argv,
      final Map<String, String> env,
      final Path dir,
      final Duration timeout,
      final ProcessExecutorGateway gateway,
      final JsonMapper json,
      final Runnable onClose) {
    this.type = type;
    this.argv = List.copyOf(argv);
    this.env = Map.copyOf(env);
    this.dir = dir;
    this.timeout = timeout;
    this.gateway = gateway;
    this.json = json;
    this.onClose = onClose;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Map<String, Object>> tool(final String name) {
    return request(LIST, Map.of())
        .expand(
            page ->
                page.get("nextCursor") instanceof String cursor
                    ? request(LIST, Map.of("cursor", cursor))
                    : Mono.empty())
        .take(MAX_PAGES)
        .flatMapIterable(page -> (List<Map<String, Object>>) page.getOrDefault("tools", List.of()))
        .filter(tool -> name.equals(tool.get("name")))
        .next()
        .switchIfEmpty(Mono.error(failure("the MCP server has no tool '" + name + "'")));
  }

  @Override
  public Mono<McpCallResult> call(final String tool, final Map<String, Object> arguments) {
    return request("tools/call", Map.of("name", tool, "arguments", arguments))
        .map(McpCallResult::fromResult);
  }

  @Override
  public synchronized void close() {
    closed = true;
    session = null;
    if (process != null) {
      process.dispose();
      process = null;
      onClose.run();
    }
  }

  private Mono<Map<String, Object>> request(final String method, final Map<String, Object> params) {
    return started().flatMap(writer -> send(writer, method, params));
  }

  /** The running server's stdin, starting it and shaking hands first when it is not running. */
  private synchronized Mono<StdinWriter> started() {
    if (closed) {
      return Mono.error(new IllegalStateException(PLUGIN + type + " is closed"));
    }
    if (session == null) {
      final Sinks.One<StdinWriter> ready = Sinks.one();
      final ProcessExecutionSpec spec =
          ProcessExecutionSpec.builder()
              .command(argv)
              .env(env)
              .workingDir(dir.toString())
              .timeoutSeconds(FOREVER_SECONDS)
              .captureStderr(true)
              .maxOutputLines(1)
              .build();
      final Mono<StdinWriter> handshake =
          ready.asMono().flatMap(writer -> initialize(writer).thenReturn(writer)).cache();
      session = handshake;
      process =
          gateway
              .executeInstalledInteractive(
                  spec, (stream, text, level) -> line(stream, text), ready::tryEmitValue)
              .doFinally(signal -> exited(handshake, ready))
              .subscribe(
                  result ->
                      log.atInfo().log(
                          "Plugin {}: MCP server exited with {}", type, result.exitCode()),
                  error ->
                      log.atWarn()
                          .log("Plugin {}: MCP server failed: {}", type, error.getMessage()));
    }
    return session;
  }

  private Mono<Void> initialize(final StdinWriter writer) {
    return send(
            writer,
            "initialize",
            Map.of(
                "protocolVersion",
                PROTOCOL_VERSION,
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "yukta", "version", "1")))
        .doOnNext(
            result ->
                writer.write(
                    json.writeValueAsString(
                        Map.of(JSONRPC, "2.0", METHOD, "notifications/initialized"))))
        .then();
  }

  private Mono<Map<String, Object>> send(
      final StdinWriter writer, final String method, final Map<String, Object> params) {
    final long id = ids.incrementAndGet();
    final Sinks.One<Map<String, Object>> reply = Sinks.one();
    pending.put(id, reply);
    final Map<String, Object> request = new LinkedHashMap<>();
    request.put(JSONRPC, "2.0");
    request.put(ID, id);
    request.put(METHOD, method);
    request.put("params", params);
    writer.write(json.writeValueAsString(request));
    return reply
        .asMono()
        .timeout(timeout, Mono.error(() -> failure(method + " timed out after " + timeout)))
        .doFinally(signal -> pending.remove(id));
  }

  /** A line from the server: a reply on stdout, a log line on stderr. */
  private void line(final String stream, final String line) {
    if (!"stdout".equals(stream)) {
      log.atInfo().log("Plugin {}: {}", type, line);
      return;
    }
    final Map<String, Object> message;
    try {
      message = json.readValue(line, Map.class);
    } catch (final JacksonException e) {
      log.atWarn().log("Plugin {}: MCP server wrote a line that is not JSON", type);
      return;
    }
    if (message.get(ID) instanceof Number id && !message.containsKey(METHOD)) {
      reply(id.longValue(), message);
    }
    // ponytail: server requests and notifications (ping, roots, progress) are ignored; answer
    // them when a server needs it.
  }

  @SuppressWarnings("unchecked")
  private void reply(final long id, final Map<String, Object> message) {
    final Sinks.One<Map<String, Object>> sink = pending.get(id);
    if (sink == null) {
      return;
    }
    if (message.get("error") instanceof Map<?, ?> error) {
      sink.tryEmitError(failure(String.valueOf(error.get("message"))));
    } else {
      sink.tryEmitValue((Map<String, Object>) message.getOrDefault(RESULT, Map.of()));
    }
  }

  /** The process ended: calls waiting for it fail, and the next call starts it again. */
  @SuppressWarnings("ReferenceEquality") // the same session, not an equal one
  private synchronized void exited(
      final Mono<StdinWriter> handshake, final Sinks.One<StdinWriter> ready) {
    if (session == handshake) {
      session = null;
      process = null;
    }
    final IllegalStateException gone = failure("the MCP server exited");
    ready.tryEmitError(gone);
    Flux.fromIterable(pending.values()).subscribe(sink -> sink.tryEmitError(gone));
  }

  private IllegalStateException failure(final String what) {
    return new IllegalStateException(PLUGIN + type + ": " + what);
  }
}
