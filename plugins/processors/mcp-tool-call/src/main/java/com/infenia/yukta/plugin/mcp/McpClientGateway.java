// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal MCP client over streamable HTTP: {@code initialize}, {@code initialized}, {@code
 * tools/call}. Sessions are cached per endpoint and credentials, and re-created when the server
 * forgets them. Replies may be plain JSON or an SSE stream; progress notifications trigger the
 * heartbeat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap",
  "PMD.OnlyOneReturn",
  "PMD.CouplingBetweenObjects"
})
public class McpClientGateway {

  private static final String PROTOCOL_VERSION = "2025-06-18";
  private static final String SESSION_HEADER = "Mcp-Session-Id";
  private static final String VERSION_HEADER = "MCP-Protocol-Version";
  private static final String JSONRPC = "jsonrpc";
  private static final String METHOD = "method";
  private static final String PARAMS = "params";
  private static final String RESULT = "result";
  private static final String PROGRESS = "notifications/progress";
  private static final long CLOSE_SECONDS = 5L;

  /** Heartbeat for requests that cannot report progress. */
  private static final Runnable NO_OP = Thread::onSpinWait;

  /** Key of a tool name. */
  private static final String NAME = "name";

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
      new ParameterizedTypeReference<>() {};

  /** Tools workflows may call; empty means any. */
  private Set<String> allowedTools = Set.of();

  /** The most bytes of tool arguments, as JSON. */
  private int maxArgumentBytes = 65_536;

  /** Arguments must not match any of these. */
  private List<Pattern> denyPatterns = List.of();

  /** Builder used to create the WebClient for each request. */
  private final WebClient.Builder webClientBuilder;

  /**
   * Open sessions by {@link McpCallSpec#sessionKey()}; the session id is empty for stateless
   * servers.
   */
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  /** A cached session handshake. */
  private record Session(McpCallSpec spec, Mono<String> sessionId) {}

  /** The server dropped our session (HTTP 404 on a request that carried one). */
  private static final class SessionExpiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /* default */ SessionExpiredException() {
      super("MCP session expired");
    }
  }

  /** A non-2xx reply from the MCP endpoint. */
  public static final class McpHttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /* default */ McpHttpException(final int status, final String body) {
      this(status, body, null);
    }

    /* default */ McpHttpException(final int status, final String body, final Throwable cause) {
      super("MCP endpoint answered HTTP " + status + ": " + body, cause);
    }
  }

  /** A JSON-RPC reply plus the session id header it came with. */
  private record Reply(String sessionId, List<Map<String, Object>> messages) {}

  /**
   * Call a tool. Tool errors are returned as data; transport failures and timeouts error. The
   * request is cancelled on the server when the subscription is cancelled.
   *
   * @param spec the resolved call
   * @param heartbeat run whenever the server reports progress, so the engine's idle timer restarts
   * @return a Mono emitting the result
   */
  public Mono<McpCallResult> call(final McpCallSpec spec, final Runnable heartbeat) {
    final String refusal = refusalFor(spec);
    if (refusal != null) {
      return Mono.error(new IllegalArgumentException(refusal));
    }
    final String requestId = UUID.randomUUID().toString();
    return Mono.defer(() -> callOnce(spec, requestId, heartbeat))
        .onErrorResume(
            SessionExpiredException.class,
            e -> {
              sessions.remove(spec.sessionKey());
              return callOnce(spec, requestId, heartbeat);
            })
        .timeout(spec.timeout())
        .doOnCancel(() -> cancelRemote(spec, requestId));
  }

  /**
   * Sets the tools workflows may call ({@code yukta.mcp-client.allowed-tools}); empty allows any.
   * This holds whatever a node says, so a model cannot pick a tool outside it.
   *
   * @param allowed tool names, or empty
   */
  @Value("${yukta.mcp-client.allowed-tools:}")
  /* default */ void setAllowedTools(final List<String> allowed) {
    this.allowedTools = Set.copyOf(allowed);
  }

  /**
   * Sets the most bytes of arguments, as JSON, one call may carry ({@code
   * yukta.mcp-client.max-argument-bytes}).
   *
   * @param maxBytes the limit
   */
  @Value("${yukta.mcp-client.max-argument-bytes:65536}")
  /* default */ void setMaxArgumentBytes(final int maxBytes) {
    this.maxArgumentBytes = maxBytes;
  }

  /**
   * Sets regular expressions the arguments must not match ({@code
   * yukta.mcp-client.deny-argument-patterns}), e.g. for secrets or injected instructions.
   *
   * @param patterns the expressions
   */
  @Value("${yukta.mcp-client.deny-argument-patterns:}")
  /* default */ void setDenyPatterns(final List<String> patterns) {
    this.denyPatterns = patterns.stream().map(Pattern::compile).toList();
  }

  private String refusalFor(final McpCallSpec spec) {
    if (!allowedTools.isEmpty() && !allowedTools.contains(spec.tool())) {
      return "MCP tool '" + spec.tool() + "' is not in yukta.mcp-client.allowed-tools";
    }
    if (spec.arguments().isEmpty()) {
      return null;
    }
    final String json = JSON_MAPPER.writeValueAsString(spec.arguments());
    if (json.length() > maxArgumentBytes) {
      return "Arguments of MCP tool '"
          + spec.tool()
          + "' exceed yukta.mcp-client.max-argument-bytes";
    }
    return denyPatterns.stream().anyMatch(p -> p.matcher(json).find())
        ? "Arguments of MCP tool '"
            + spec.tool()
            + "' match yukta.mcp-client.deny-argument-patterns"
        : null;
  }

  /**
   * List the tools the server offers (first page).
   *
   * @param spec the endpoint, headers and time limit; {@code tool} and {@code arguments} are unused
   * @param heartbeat run whenever the server reports progress
   * @return a Mono emitting {@code {name, description, inputSchema}} per tool
   */
  public Mono<List<Map<String, Object>>> listTools(
      final McpCallSpec spec, final Runnable heartbeat) {
    final String requestId = UUID.randomUUID().toString();
    return Mono.defer(() -> listOnce(spec, requestId, heartbeat))
        .onErrorResume(
            SessionExpiredException.class,
            e -> {
              sessions.remove(spec.sessionKey());
              return listOnce(spec, requestId, heartbeat);
            })
        .timeout(spec.timeout());
  }

  private Mono<List<Map<String, Object>>> listOnce(
      final McpCallSpec spec, final String requestId, final Runnable heartbeat) {
    return session(spec)
        .flatMap(
            sessionId ->
                post(spec, sessionId, request(requestId, "tools/list", Map.of()), heartbeat))
        .map(reply -> toTools(reply, requestId));
  }

  private Mono<McpCallResult> callOnce(
      final McpCallSpec spec, final String requestId, final Runnable heartbeat) {
    return session(spec)
        .flatMap(
            sessionId -> {
              final Map<String, Object> params = new LinkedHashMap<>();
              params.put(NAME, spec.tool());
              params.put("arguments", spec.arguments());
              final Map<String, Object> meta = new LinkedHashMap<>(spec.meta());
              meta.put("progressToken", requestId);
              params.put("_meta", meta);
              return post(spec, sessionId, request(requestId, "tools/call", params), heartbeat);
            })
        .map(reply -> toResult(reply, requestId));
  }

  private Mono<String> session(final McpCallSpec spec) {
    return sessions
        .computeIfAbsent(
            spec.sessionKey(),
            key -> new Session(spec, initialize(spec).doOnError(e -> sessions.remove(key)).cache()))
        .sessionId();
  }

  private Mono<String> initialize(final McpCallSpec spec) {
    final Map<String, Object> params =
        Map.of(
            "protocolVersion",
            PROTOCOL_VERSION,
            "capabilities",
            Map.of(),
            "clientInfo",
            Map.of(NAME, "yukta", "version", "1"));
    return post(spec, "", request(UUID.randomUUID().toString(), "initialize", params), NO_OP)
        .flatMap(
            reply -> {
              if (reply.messages().stream().anyMatch(m -> m.containsKey("error"))) {
                return Mono.error(new McpHttpException(200, String.valueOf(reply.messages())));
              }
              return post(spec, reply.sessionId(), notification("notifications/initialized"), NO_OP)
                  .thenReturn(reply.sessionId());
            });
  }

  private void cancelRemote(final McpCallSpec spec, final String requestId) {
    final Session cached = sessions.get(spec.sessionKey());
    if (cached == null) {
      return;
    }
    cached
        .sessionId()
        .flatMap(
            sessionId ->
                post(
                    spec,
                    sessionId,
                    notification(
                        "notifications/cancelled",
                        Map.of("requestId", requestId, "reason", "workflow cancelled")),
                    NO_OP))
        .onErrorComplete()
        .subscribe();
  }

  /** Forget every cached session so the next call re-initializes (control-bus "reset"). */
  public void reset() {
    sessions.clear();
  }

  /** Terminate server sessions on application shutdown; the engine never calls plugin shutdown. */
  @jakarta.annotation.PreDestroy
  public void close() {
    final List<Session> open = new ArrayList<>(sessions.values());
    sessions.clear();
    Flux.fromIterable(open)
        .flatMap(
            s ->
                s.sessionId()
                    .filter(sessionId -> !sessionId.isEmpty())
                    .flatMap(
                        sessionId ->
                            webClientBuilder
                                .build()
                                .method(HttpMethod.DELETE)
                                .uri(s.spec().url())
                                .headers(h -> s.spec().headers().forEach(h::add))
                                .header(SESSION_HEADER, sessionId)
                                .retrieve()
                                .toBodilessEntity())
                    .onErrorComplete())
        .blockLast(Duration.ofSeconds(CLOSE_SECONDS));
  }

  private Mono<Reply> post(
      final McpCallSpec spec,
      final String sessionId,
      final Map<String, Object> body,
      final Runnable heartbeat) {
    return webClientBuilder
        .build()
        .post()
        .uri(spec.url())
        .headers(h -> spec.headers().forEach(h::add))
        .headers(
            h -> {
              if (!sessionId.isEmpty()) {
                h.add(SESSION_HEADER, sessionId);
                h.add(VERSION_HEADER, PROTOCOL_VERSION);
              }
            })
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .bodyValue(body)
        .exchangeToMono(
            response ->
                messages(response, !sessionId.isEmpty(), heartbeat)
                    .collectList()
                    .map(
                        list ->
                            new Reply(
                                response.headers().header(SESSION_HEADER).stream()
                                    .findFirst()
                                    .orElse(sessionId),
                                list)));
  }

  private Flux<Map<String, Object>> messages(
      final ClientResponse response, final boolean hadSession, final Runnable heartbeat) {
    final int status = response.statusCode().value();
    if (status == 404 && hadSession) {
      return response.releaseBody().thenMany(Flux.error(new SessionExpiredException()));
    }
    if (!response.statusCode().is2xxSuccessful()) {
      return response
          .bodyToMono(String.class)
          .defaultIfEmpty("")
          .flatMapMany(text -> Flux.error(new McpHttpException(status, text)));
    }
    final boolean sse =
        response
            .headers()
            .contentType()
            .filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
            .isPresent();
    final Flux<String> raw =
        sse
            ? response.bodyToFlux(SSE).mapNotNull(ServerSentEvent::data)
            : response.bodyToMono(String.class).flux();
    return raw.filter(text -> !text.isBlank())
        .map(McpClientGateway::parse)
        .doOnNext(
            message -> {
              if (PROGRESS.equals(message.get(METHOD))) {
                heartbeat.run();
              }
            });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(final String text) {
    try {
      return (Map<String, Object>) JSON_MAPPER.readValue(text, Object.class);
    } catch (JacksonException | ClassCastException e) {
      throw new McpHttpException(200, "unparseable JSON-RPC message: " + text, e);
    }
  }

  private static Map<String, Object> answerFor(final Reply reply, final String requestId) {
    return reply.messages().stream()
        .filter(m -> requestId.equals(m.get("id")))
        .findFirst()
        .orElseThrow(() -> new McpHttpException(200, "no reply for request " + requestId));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> toTools(final Reply reply, final String requestId) {
    final Map<String, Object> answer = answerFor(reply, requestId);
    if (answer.get("error") instanceof final Map<?, ?> error) {
      throw new McpHttpException(200, "tools/list failed: " + error.get("message"));
    }
    final Map<String, Object> result = (Map<String, Object>) answer.getOrDefault(RESULT, Map.of());
    return ((List<Object>) result.getOrDefault("tools", List.of()))
        .stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .map(
                tool ->
                    Map.<String, Object>of(
                        NAME,
                        String.valueOf(tool.get(NAME)),
                        "description",
                        String.valueOf(tool.getOrDefault("description", "")),
                        "inputSchema",
                        tool.getOrDefault("inputSchema", Map.of())))
            .toList();
  }

  @SuppressWarnings("unchecked")
  private static McpCallResult toResult(final Reply reply, final String requestId) {
    final Map<String, Object> answer = answerFor(reply, requestId);
    if (answer.get("error") instanceof final Map<?, ?> error) {
      return McpCallResult.transportFailure(String.valueOf(error.get("message")));
    }
    final Map<String, Object> result = (Map<String, Object>) answer.getOrDefault(RESULT, Map.of());
    final List<Object> content = (List<Object>) result.getOrDefault("content", List.of());
    final String text =
        content.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .filter(item -> "text".equals(item.get("type")))
            .map(item -> String.valueOf(item.get("text")))
            .collect(Collectors.joining("\n"));
    final boolean isError = Boolean.TRUE.equals(result.get("isError"));
    return new McpCallResult(
        isError, text, content, result.get("structuredContent"), isError ? text : null);
  }

  private static Map<String, Object> request(
      final String requestId, final String method, final Map<String, Object> params) {
    return Map.of(JSONRPC, "2.0", "id", requestId, METHOD, method, PARAMS, params);
  }

  private static Map<String, Object> notification(final String method) {
    return Map.of(JSONRPC, "2.0", METHOD, method);
  }

  private static Map<String, Object> notification(
      final String method, final Map<String, Object> params) {
    return Map.of(JSONRPC, "2.0", METHOD, method, PARAMS, params);
  }
}
