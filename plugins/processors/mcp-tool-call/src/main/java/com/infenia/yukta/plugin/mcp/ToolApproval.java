// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKeys;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * The {@code requireApproval} option of {@code MCP_TOOL_CALL}: a list of tool names (or {@code *}
 * for all) that wait for a human signal before they are called. The question is an input request
 * (kind {@code APPROVAL}, risk {@code HIGH}) answered with the input answer command, so it is
 * reviewed and audited like any command; a {@code reject} decision or no answer within {@code
 * approvalTimeoutSeconds} refuses the call.
 */
@UtilityClass
class ToolApproval {

  /** Config key: tools that need an approval. */
  /* default */ static final String REQUIRE = "requireApproval";

  /** Config key: name of the signal that answers. */
  /* default */ static final String SIGNAL = "approvalSignal";

  /** Config key: longest wait in seconds. */
  /* default */ static final String TIMEOUT = "approvalTimeoutSeconds";

  /** Tool name that stands for every tool. */
  private static final String ALL = "*";

  /** Signal name used when none is configured. */
  private static final String DEFAULT_SIGNAL = "approve";

  /** Label of this plugin on its requests. */
  private static final String REQUESTER = "MCP_TOOL_CALL";

  /** Most of the tool name shown in the prompt, in bytes. */
  private static final int TOOL_SHOWN_BYTES = 200;

  /** Most of the arguments shown in the prompt, in bytes. */
  private static final int ARGUMENTS_SHOWN_BYTES = 2000;

  /** Wait used when none is configured. */
  private static final long DEFAULT_SECONDS = 3600;

  /**
   * Whether the tool needs an approval.
   *
   * @param config the node config
   * @param tool the tool about to be called
   * @return true if it is listed (or all tools are)
   */
  /* default */ boolean required(final Map<String, Object> config, final String tool) {
    return config.get(REQUIRE) instanceof final List<?> tools
        && (tools.contains(ALL) || tools.contains(tool));
  }

  /**
   * How long the call may wait for the approval, which the node timeout must outlast.
   *
   * @param config the node config
   * @return zero if no tool needs one
   */
  /* default */ Duration wait(final Map<String, Object> config) {
    return config.get(REQUIRE) instanceof final List<?> tools && !tools.isEmpty()
        ? Duration.ofSeconds(seconds(config))
        : Duration.ZERO;
  }

  /**
   * Asks for the approval of a call and waits for it. The question is an input request of kind
   * {@code APPROVAL} and risk {@code HIGH}, so only a person can answer it.
   *
   * @param gateway where the input gateway is found
   * @param config the node config
   * @param ctx the context of the running node
   * @param messageId the id of the message the call is made for
   * @param call the call about to be made
   * @return completes when approved; fails when rejected or not answered in time
   */
  /* default */ Mono<Void> await(
      final Supplier<InputGateway> gateway,
      final Map<String, Object> config,
      final ContextView ctx,
      final String messageId,
      final McpCallSpec call) {
    final String tool = call.tool();
    final String name = String.valueOf(config.getOrDefault(SIGNAL, DEFAULT_SIGNAL));
    final Duration timeout = Duration.ofSeconds(seconds(config));
    return Mono.fromSupplier(() -> spec(ctx, messageId, name, timeout, call))
        .flatMap(spec -> InputAsk.ask(gateway, spec))
        .onErrorMap(
            InputAsk::isTimeout,
            e ->
                new IllegalArgumentException(
                    "No '" + name + "' approval for tool '" + tool + "' within " + timeout, e))
        .flatMap(
            response ->
                "reject".equalsIgnoreCase(response.decision())
                    ? Mono.<Void>error(
                        new IllegalArgumentException(
                            "Tool '"
                                + tool
                                + "' was rejected: "
                                + Objects.requireNonNullElse(response.text(), "")))
                    : Mono.<Void>empty());
  }

  /**
   * The key part that tells the calls of one message apart. Hashes the raw tool and arguments, so
   * an approval covers only the call it was asked for; a replay of the same call still matches.
   *
   * @param call the call about to be made
   * @return the hex SHA-256 of the tool and arguments
   */
  private static String digest(final McpCallSpec call) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (call.tool() + '\0' + call.arguments()).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static InputRequestSpec spec(
      final ContextView ctx,
      final String messageId,
      final String name,
      final Duration timeout,
      final McpCallSpec call) {
    final InputIdentity who = InputIdentity.from(ctx);
    final String tool = InputAsk.clean(call.tool(), TOOL_SHOWN_BYTES);
    final String arguments =
        InputAsk.clean(String.valueOf(call.arguments()), ARGUMENTS_SHOWN_BYTES);
    return new InputRequestSpec(
        InputKeys.of(
            who.sessionId(), who.workflowId(), who.nodeId(), messageId, name + ':' + digest(call)),
        InputKind.APPROVAL,
        "Tool '" + tool + "' wants to run with " + arguments + ". Approve or reject.",
        Map.of(InputAsk.SIGNAL_CONTEXT_KEY, name, "tool", tool, "arguments", arguments),
        List.of(),
        null,
        InputRisk.HIGH,
        Audience.CURRENT_TIER,
        timeout,
        TimeoutAction.FAIL,
        null,
        null,
        REQUESTER);
  }

  /**
   * Checks the options.
   *
   * @param config the node config
   * @throws IllegalArgumentException if an option is malformed
   */
  /* default */ void validate(final Map<String, Object> config) {
    if (config.get(REQUIRE) != null
        && !(config.get(REQUIRE) instanceof final List<?> tools
            && tools.stream().allMatch(t -> t instanceof final String name && !name.isBlank()))) {
      throw new IllegalArgumentException("requireApproval must be a list of tool names or *");
    }
    if (config.get(TIMEOUT) != null
        && !(config.get(TIMEOUT) instanceof final Number number && number.longValue() > 0)) {
      throw new IllegalArgumentException("approvalTimeoutSeconds must be a positive number");
    }
  }

  private static long seconds(final Map<String, Object> config) {
    return config.get(TIMEOUT) instanceof final Number number
        ? number.longValue()
        : DEFAULT_SECONDS;
  }
}
