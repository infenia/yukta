// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.callout;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.AttachmentAware;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorResultMapper;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.DirectiveSupervisor;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A supervisor that is a web service. It posts the events it is asked for, and the commands to
 * review when {@code review} is set, and reads commands or a verdict from the reply.
 *
 * <p>What it sends is masked (values under secret-looking keys). What comes back is not trusted:
 * commands pass the allow-list of {@link SupervisorResultMapper} and then the supervisor's own
 * {@code allowedCommands}, at most {@value #MAX_COMMANDS} per reply; a timeout, an error status or
 * a reply that cannot be read is no opinion for an event, and for a review a refusal or an
 * approval, as {@code reviewOnError} says. No log line or error message holds a header value.
 */
@Slf4j
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
public final class CalloutSupervisor implements DirectiveSupervisor, AttachmentAware {

  /** Most commands taken from one reply. */
  public static final int MAX_COMMANDS = 10;

  /** Longest reason kept from a reply. */
  private static final int MAX_REASON = 200;

  private static final String ALLOW = "allow";

  private static final String DENY = "deny";

  private static final String REQUIRE_APPROVAL = "requireApproval";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String supervisorId;

  private final Config config;

  private final Set<String> allowed;

  private final WebClient client;

  private final Egress egress;

  private String attachmentId;

  private final java.util.concurrent.atomic.AtomicLong sequence =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * Creates the supervisor.
   *
   * @param supervisorId the id of the supervisor it was made from
   * @param config what it is set to
   * @param allowed the command types it may send, by simple name
   * @param egress decides whether the URL may be called
   */
  public CalloutSupervisor(
      final String supervisorId,
      final Config config,
      final Set<String> allowed,
      final Egress egress) {
    this.supervisorId = supervisorId;
    this.attachmentId = supervisorId;
    this.config = config;
    this.allowed = Set.copyOf(allowed);
    this.egress = egress;
    this.client = WebClient.builder().build();
  }

  @Override
  public void bindTo(final String id) {
    this.attachmentId = id;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return attachmentId;
  }

  @Override
  public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
    return events
        .filter(event -> config.events().contains(event.getClass().getSimpleName()))
        .flatMap(
            event ->
                post(payload("event", "event", eventMap(event)))
                    .flatMapMany(reply -> commands(reply, event.executionId()))
                    .map(
                        command -> {
                          final long n = sequence.incrementAndGet();
                          // reactive only: a reply answers one event, so it never acts on its own
                          return new Directive.Command(
                              supervisorId + ":" + n, command, Cause.event("c" + n), null, null);
                        })
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .addKeyValue("supervisor", supervisorId)
                              .addKeyValue("error", error.getClass().getSimpleName())
                              .log("The webhook gave no opinion about an event");
                          return Flux.empty();
                        }),
            config.maxConcurrent());
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    if (!config.review()) {
      return Mono.just(Decision.ALLOW);
    }
    return post(payload("review", "request", requestMap(request)))
        .map(CalloutSupervisor::verdict)
        .onErrorResume(
            error -> {
              log.atWarn()
                  .addKeyValue("supervisor", supervisorId)
                  .addKeyValue("error", error.getClass().getSimpleName())
                  .log("The webhook gave no verdict on a command");
              return Mono.just(
                  config.denyOnError()
                      ? Decision.deny("the webhook did not answer")
                      : Decision.ALLOW);
            });
  }

  private Map<String, Object> payload(final String kind, final String key, final Object body) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("kind", kind);
    out.put("supervisorId", supervisorId);
    out.put("attachmentId", attachmentId);
    out.put(key, body);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object eventMap(final ExecutionEvent event) {
    final Map<String, Object> map = JSON.convertValue(event, Map.class);
    map.put("type", event.getClass().getSimpleName());
    return Redaction.mask(map);
  }

  @SuppressWarnings("unchecked")
  private static Object requestMap(final ControlRequest request) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("command", request.command().getClass().getSimpleName());
    out.put("fields", Redaction.mask(JSON.convertValue(request.command(), Map.class)));
    out.put("actor", request.actor());
    out.put("sessionId", request.sessionId());
    out.put("workflowId", request.workflowId());
    out.put("nodeId", request.nodeId());
    return out;
  }

  /** Posts a body and reads the reply as a JSON object. */
  private Mono<Map<?, ?>> post(final Map<String, Object> body) {
    return egress
        .check(config.url())
        .then(
            Mono.defer(
                () ->
                    client
                        .post()
                        .uri(URI.create(config.url()))
                        .headers(headers -> config.headers().forEach(headers::set))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(config.timeout())))
        .map(CalloutSupervisor::object);
  }

  private static Map<?, ?> object(final String text) {
    final Object value = JSON.readValue(text, Object.class);
    if (value instanceof final Map<?, ?> map) {
      return map;
    }
    throw new IllegalArgumentException("The reply is not a JSON object");
  }

  private Flux<ExecutionControlCommand> commands(final Map<?, ?> reply, final String executionId) {
    return Flux.fromIterable(
        SupervisorResultMapper.map(List.of(reply), allowed, executionId, MAX_COMMANDS)
            .commands()
            .stream()
            .filter(command -> allowed.contains(command.getClass().getSimpleName()))
            .toList());
  }

  private static Decision verdict(final Map<?, ?> reply) {
    final Object decision = reply.get("decision");
    final String reason = reason(reply.get("reason"));
    if (ALLOW.equals(decision)) {
      return Decision.ALLOW;
    }
    if (DENY.equals(decision)) {
      return Decision.deny(reason == null ? "refused by the webhook" : reason);
    }
    if (REQUIRE_APPROVAL.equals(decision)) {
      return Decision.requireApproval(
          reason == null ? "approval required by the webhook" : reason, ApprovalNeed.DEFAULT);
    }
    throw new IllegalArgumentException("The reply has no valid decision");
  }

  private static @Nullable String reason(final @Nullable Object value) {
    if (!(value instanceof final String text) || text.isBlank()) {
      return null;
    }
    return text.length() > MAX_REASON ? text.substring(0, MAX_REASON) : text;
  }

  /**
   * What the webhook is set to.
   *
   * @param url the endpoint
   * @param headers the headers to send, secrets included; never logged
   * @param events the event types to post
   * @param timeout how long to wait for a reply
   * @param review whether commands are posted for review
   * @param denyOnError whether a review that fails is refused (otherwise allowed)
   * @param maxConcurrent most calls at once
   */
  public record Config(
      String url,
      Map<String, String> headers,
      Set<String> events,
      Duration timeout,
      boolean review,
      boolean denyOnError,
      int maxConcurrent) {

    /** Keeps immutable copies. */
    public Config {
      headers = Map.copyOf(headers);
      events = Set.copyOf(events);
    }

    /** Hides the headers: they may hold secrets. */
    @Override
    public String toString() {
      return "Config[url=" + url + "]";
    }
  }
}
