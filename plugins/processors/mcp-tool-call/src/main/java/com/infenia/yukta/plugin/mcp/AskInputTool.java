// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKeys;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimitException;
import com.infenia.yukta.plugin.input.InputLimits;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * The {@code ask_for_input} tool of {@code MCP_TOOL_CALL}, for an AI agent that wants a decision or
 * an answer while it works. It is served by this node, not by the MCP server, and only when the
 * node sets {@code allowAskInput: true}. The prompt, options, kind and risk come from the model and
 * are untrusted: they are cleaned and cut to the input limits, and the risk cannot go below {@code
 * inputRiskFloor}. Who may answer ({@code inputAudience}), for how long ({@code inputTtlSeconds})
 * and by which route ({@code inputRoute}) come from the node's config only, never from the model.
 * The answer goes back to the model as a tool result, not as an instruction: a person or an agent
 * wrote it.
 */
@UtilityClass
@SuppressWarnings({
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.CyclomaticComplexity",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.UseConcurrentHashMap"
})
class AskInputTool {

  /** The tool name the model calls. */
  /* default */ static final String NAME = "ask_for_input";

  /** Config key: turns the tool on. */
  /* default */ static final String ALLOW = "allowAskInput";

  /** Config key: lowest risk a request of the model can have. */
  /* default */ static final String RISK_FLOOR = "inputRiskFloor";

  /** Config key: how long a question lives. */
  /* default */ static final String TTL = "inputTtlSeconds";

  /** Config key: who may answer. */
  /* default */ static final String AUDIENCE = "inputAudience";

  /** Config key: the route of the request. */
  /* default */ static final String ROUTE = "inputRoute";

  /** Config key: most questions per node run. */
  /* default */ static final String MAX_ASKS = "inputMaxAsks";

  /** Config key: what no answer in time does, {@code ERROR} (a tool error) or {@code FAIL}. */
  /* default */ static final String ON_TIMEOUT = "inputOnTimeout";

  /** Questions per node run when the node does not set a limit. */
  private static final int DEFAULT_MAX_ASKS = 3;

  /** The value of {@code inputOnTimeout} that fails the node. */
  private static final String FAIL = "FAIL";

  /** Life of a question when the node does not set one. */
  private static final long DEFAULT_TTL_SECONDS = 3600;

  /** Kinds a model may ask for. */
  private static final Set<InputKind> KINDS =
      EnumSet.of(InputKind.APPROVAL, InputKind.CHOICE, InputKind.TEXT);

  /** Label of this plugin on its requests. */
  private static final String REQUESTER = "MCP_TOOL_CALL";

  /** Option text cap, in bytes. */
  private static final int OPTION_MAX_BYTES = 120;

  /** Node runs counted; the oldest are forgotten after that. */
  private static final int COUNTED_RUNS = 10_000;

  /** Questions asked so far by each node run. */
  private static final Map<String, AtomicInteger> ASKED =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, AtomicInteger> eldest) {
              return size() > COUNTED_RUNS;
            }
          });

  /**
   * Whether the call is a question the node serves itself.
   *
   * @param config the node config
   * @param tool the tool about to be called
   * @return true if the tool is {@code ask_for_input} and the node allows it
   */
  /* default */ boolean serves(final Map<String, Object> config, final String tool) {
    return NAME.equals(tool) && Boolean.TRUE.equals(config.get(ALLOW));
  }

  /**
   * How long a question may wait, which the node timeout must outlast.
   *
   * @param config the node config
   * @return zero when the tool is off
   */
  /* default */ Duration wait(final Map<String, Object> config) {
    return Boolean.TRUE.equals(config.get(ALLOW)) ? ttl(config) : Duration.ZERO;
  }

  /**
   * Checks the options.
   *
   * @param config the node config
   * @throws IllegalArgumentException if an option is malformed
   */
  /* default */ void validate(final Map<String, Object> config) {
    if (config.get(ALLOW) != null && !(config.get(ALLOW) instanceof Boolean)) {
      throw new IllegalArgumentException("allowAskInput must be true or false");
    }
    ttl(config);
    InputAsk.risk(config.get(RISK_FLOOR), InputRisk.MEDIUM);
    InputAsk.audience(config.get(AUDIENCE));
    maxAsks(config);
    onTimeoutFails(config);
    if (config.get(ROUTE) != null && !(config.get(ROUTE) instanceof String)) {
      throw new IllegalArgumentException("inputRoute must be a route name");
    }
  }

  /**
   * Asks the question of the model and waits for the answer.
   *
   * @param gateway where the input gateway is found
   * @param config the node config
   * @param ctx the context of the running node
   * @param messageId the id of the message the call is made for
   * @param arguments the arguments the model gave
   * @return the answer as a tool result; a refusal, a limit or no answer in time is a tool error
   *     the model can react to, unless {@code inputOnTimeout} is {@code FAIL}, which fails the node
   */
  /* default */ Mono<McpCallResult> ask(
      final Supplier<InputGateway> gateway,
      final Map<String, Object> config,
      final ContextView ctx,
      final String messageId,
      final Map<String, Object> arguments) {
    final InputRequestSpec spec;
    try {
      final InputIdentity who = InputIdentity.from(ctx);
      if (!countAsk(who, maxAsks(config))) {
        return Mono.just(
            failure("The node already asked " + maxAsks(config) + " questions in this run"));
      }
      spec = spec(config, who, messageId, arguments);
    } catch (IllegalArgumentException e) {
      return Mono.just(failure(e.getMessage()));
    } catch (IllegalStateException e) {
      // not inside a node: a defect of the caller, not something the model can react to
      return Mono.error(e);
    }
    return InputAsk.ask(gateway, spec)
        .map(AskInputTool::answer)
        .onErrorResume(
            InputLimitException.class, e -> Mono.just(failure("Not asked: " + e.getMessage())))
        .onErrorResume(
            InputAsk::isTimeout,
            e ->
                onTimeoutFails(config)
                    ? Mono.error(e)
                    : Mono.just(
                        failure("Nobody answered within " + ttl(config).toSeconds() + " s")));
  }

  private static InputRequestSpec spec(
      final Map<String, Object> config,
      final InputIdentity who,
      final String messageId,
      final Map<String, Object> arguments) {
    final String prompt =
        InputAsk.clean(
            arguments.get("prompt") instanceof final String text ? text : null,
            InputLimits.PROMPT_MAX_BYTES);
    if (prompt.isBlank()) {
      throw new IllegalArgumentException("ask_for_input needs a prompt");
    }
    final InputKind kind = InputAsk.kind(arguments.get("kind"), InputKind.APPROVAL);
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("kind must be one of " + KINDS + ", not " + kind);
    }
    final List<InputOption> options = options(kind, arguments.get("options"));
    final InputRisk floor = InputAsk.risk(config.get(RISK_FLOOR), InputRisk.MEDIUM);
    final InputRisk asked = InputAsk.risk(arguments.get("risk"), floor);
    final InputRisk risk = asked.atLeast(floor) ? asked : floor;
    final String key =
        InputKeys.of(
            who.sessionId(),
            who.workflowId(),
            who.nodeId(),
            messageId,
            "ask:" + UUID.nameUUIDFromBytes(prompt.getBytes(StandardCharsets.UTF_8)));
    return new InputRequestSpec(
        key,
        kind,
        prompt,
        Map.of("askedBy", "ask_for_input"),
        options,
        null,
        risk,
        InputAsk.audience(config.get(AUDIENCE)),
        ttl(config),
        TimeoutAction.FAIL,
        null,
        config.get(ROUTE) instanceof final String route && !route.isBlank() ? route : null,
        REQUESTER);
  }

  private static List<InputOption> options(final InputKind kind, final Object value) {
    if (kind == InputKind.TEXT) {
      return List.of();
    }
    final List<InputOption> options = new ArrayList<>();
    if (value instanceof final List<?> list) {
      for (final Object entry : list) {
        final String id = InputAsk.clean(String.valueOf(entry), OPTION_MAX_BYTES);
        if (!id.isBlank() && options.stream().noneMatch(o -> o.id().equals(id))) {
          options.add(new InputOption(id));
        }
      }
    }
    if (options.size() > InputLimits.OPTIONS_MAX) {
      throw new IllegalArgumentException(
          "at most " + InputLimits.OPTIONS_MAX + " options, not " + options.size());
    }
    if (kind == InputKind.CHOICE && options.size() < 2) {
      throw new IllegalArgumentException("a choice needs at least two options");
    }
    return options;
  }

  private static McpCallResult answer(final InputResponse response) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("requestId", response.requestId());
    data.put("decision", response.decision());
    data.put("text", response.text());
    data.put("data", response.data());
    data.put("responder", response.responder().type() + ":" + response.responder().id());
    final String said = response.text();
    final String text =
        said == null || said.isBlank() ? response.decision() : response.decision() + ": " + said;
    return new McpCallResult(false, text, List.of(), data, null);
  }

  private static McpCallResult failure(final String message) {
    return McpCallResult.transportFailure(message);
  }

  /** Counts a question; false when the node run has used its share. */
  /* default */ boolean countAsk(final InputIdentity who, final int max) {
    final AtomicInteger asked =
        ASKED.computeIfAbsent(who.executionId() + '\0' + who.nodeId(), _ -> new AtomicInteger());
    return asked.incrementAndGet() <= max;
  }

  private static Duration ttl(final Map<String, Object> config) {
    final Object value = config.getOrDefault(TTL, DEFAULT_TTL_SECONDS);
    if (value instanceof final Number seconds && seconds.longValue() > 0) {
      return Duration.ofSeconds(seconds.longValue());
    }
    throw new IllegalArgumentException("inputTtlSeconds must be a positive number");
  }

  private static int maxAsks(final Map<String, Object> config) {
    final Object value = config.getOrDefault(MAX_ASKS, DEFAULT_MAX_ASKS);
    if (value instanceof final Number number && number.intValue() > 0) {
      return number.intValue();
    }
    throw new IllegalArgumentException("inputMaxAsks must be a positive number");
  }

  private static boolean onTimeoutFails(final Map<String, Object> config) {
    final Object value = config.get(ON_TIMEOUT);
    if (value == null || "ERROR".equals(value)) {
      return false;
    }
    if (FAIL.equals(value)) {
      return true;
    }
    throw new IllegalArgumentException("inputOnTimeout must be ERROR or FAIL");
  }
}
