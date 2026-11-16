// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 2.0 over lines, as the Agent Client Protocol speaks it: numbers the requests Yukta sends
 * and gives each answer to its request, and hands requests and notifications of the agent on. The
 * id of an agent request is echoed as it came, a string or a number.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // local maps, never shared
final class AcpWire {

  private static final String JSONRPC = "jsonrpc";
  private static final String VERSION = "2.0";
  private static final String ID = "id";
  private static final String METHOD = "method";
  private static final String PARAMS = "params";

  private final ProcessExecutorGateway.StdinWriter stdin;
  private final ObjectMapper json;
  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Sinks.One<JsonNode>> pending = new ConcurrentHashMap<>();

  /* default */ AcpWire(final ProcessExecutorGateway.StdinWriter stdin, final ObjectMapper json) {
    this.stdin = stdin;
    this.json = json;
  }

  /**
   * Sends a request.
   *
   * @param method the method
   * @param params the parameters
   * @return the {@code result} of the answer; an error answer is an {@link RpcException}
   */
  /* default */ Mono<JsonNode> request(final String method, final Object params) {
    final long id = ids.incrementAndGet();
    final Sinks.One<JsonNode> answer = Sinks.one();
    pending.put(id, answer);
    stdin.write(frame(method, params, json.getNodeFactory().numberNode(id)));
    return answer.asMono().doFinally(_ -> pending.remove(id));
  }

  /**
   * Sends a notification.
   *
   * @param method the method
   * @param params the parameters
   */
  /* default */ void notify(final String method, final Object params) {
    stdin.write(frame(method, params, null));
  }

  /**
   * Answers a request of the agent.
   *
   * @param id the id the request had
   * @param result the result
   */
  /* default */ void reply(final JsonNode id, final Object result) {
    stdin.write(replyLine(id, result));
  }

  /**
   * Writes the answer to a request of the agent as a line, for a caller that writes it later.
   *
   * @param id the id the request had
   * @param result the result
   * @return the line
   */
  /* default */ String replyLine(final JsonNode id, final Object result) {
    final Map<String, Object> out = envelope(id);
    out.put("result", result);
    return json.writeValueAsString(out);
  }

  /**
   * Refuses a request of the agent.
   *
   * @param id the id the request had
   * @param code the JSON-RPC error code
   * @param message why
   */
  /* default */ void fail(final JsonNode id, final int code, final String message) {
    final Map<String, Object> out = envelope(id);
    final Map<String, Object> error = new java.util.LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);
    out.put("error", error);
    stdin.write(json.writeValueAsString(out));
  }

  /**
   * Reads one line of the agent.
   *
   * @param line the line
   * @param peer gets requests and notifications
   * @return false when the line is not a JSON-RPC message (log text, for example)
   */
  /* default */ boolean accept(final String line, final Listener peer) {
    final JsonNode node = parse(line);
    if (node == null) {
      return false;
    }
    final JsonNode id = node.path(ID);
    final boolean hasId = !id.isMissingNode() && !id.isNull();
    final JsonNode method = node.path(METHOD);
    if (method.isString()) {
      if (hasId) {
        peer.request(id, method.asString(), node.path(PARAMS));
      } else {
        peer.notification(method.asString(), node.path(PARAMS));
      }
      return true;
    }
    if (!hasId) {
      return false;
    }
    answer(id, node);
    return true;
  }

  private void answer(final JsonNode id, final JsonNode node) {
    final Sinks.One<JsonNode> waiting = pending.get(id.asLong());
    if (waiting == null) {
      return;
    }
    final JsonNode error = node.path("error");
    if (error.isObject()) {
      waiting.tryEmitError(new RpcException(error.path("message").asString("agent error")));
    } else {
      waiting.tryEmitValue(node.path("result"));
    }
  }

  private String frame(final String method, final Object params, final JsonNode id) {
    final Map<String, Object> out = envelope(id);
    out.put(METHOD, method);
    out.put(PARAMS, params);
    return json.writeValueAsString(out);
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap") // a local map, never shared
  private static Map<String, Object> envelope(final JsonNode id) {
    final Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put(JSONRPC, VERSION);
    if (id != null) {
      out.put(ID, id);
    }
    return out;
  }

  private JsonNode parse(final String line) {
    if (!line.startsWith("{")) {
      return null;
    }
    try {
      return json.readTree(line);
    } catch (JacksonException e) {
      return null;
    }
  }

  /** What the agent sends that is not an answer. */
  /* default */ interface Listener {

    /**
     * A request the agent expects an answer to.
     *
     * @param id its id, to answer with
     * @param method the method
     * @param params the parameters
     */
    void request(JsonNode id, String method, JsonNode params);

    /**
     * A notification, which has no answer.
     *
     * @param method the method
     * @param params the parameters
     */
    void notification(String method, JsonNode params);
  }

  /** The agent answered a request with an error. */
  /* default */ static final class RpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /* default */ RpcException(final String message) {
      super(message);
    }
  }
}
