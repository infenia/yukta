// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import com.infenia.yukta.plugin.util.SignalWaits;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/**
 * The {@code requireApproval} option of {@code MCP_TOOL_CALL}: a list of tool names (or {@code *}
 * for all) that wait for a human signal before they are called. The signal is sent with {@code
 * signal_node} (REST, MCP, CLI), so it is reviewed and audited like any command; a {@code reject}
 * decision or no answer within {@code approvalTimeoutSeconds} refuses the call.
 */
@UtilityClass
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn"})
class ToolApproval {

  /** Config key: tools that need an approval. */
  static final String REQUIRE = "requireApproval";

  /** Config key: name of the signal that answers. */
  static final String SIGNAL = "approvalSignal";

  /** Config key: longest wait in seconds. */
  static final String TIMEOUT = "approvalTimeoutSeconds";

  private static final String ALL = "*";
  private static final String DEFAULT_SIGNAL = "approve";
  private static final long DEFAULT_SECONDS = 3600;

  /**
   * Whether the tool needs an approval.
   *
   * @param config the node config
   * @param tool the tool about to be called
   * @return true if it is listed (or all tools are)
   */
  boolean required(final Map<String, Object> config, final String tool) {
    return config.get(REQUIRE) instanceof final List<?> tools
        && (tools.contains(ALL) || tools.contains(tool));
  }

  /**
   * How long the call may wait for the approval, which the node timeout must outlast.
   *
   * @param config the node config
   * @return zero if no tool needs one
   */
  Duration wait(final Map<String, Object> config) {
    return config.get(REQUIRE) instanceof final List<?> tools && !tools.isEmpty()
        ? Duration.ofSeconds(seconds(config))
        : Duration.ZERO;
  }

  /**
   * Waits for the approval of a call.
   *
   * @param waits the waits of the plugin
   * @param config the node config
   * @param executionId the execution
   * @param nodeId the node
   * @param tool the tool about to be called
   * @return completes when approved; fails when rejected or not answered in time
   */
  Mono<Void> await(
      final SignalWaits waits,
      final Map<String, Object> config,
      final String executionId,
      final String nodeId,
      final String tool) {
    final String name = String.valueOf(config.getOrDefault(SIGNAL, DEFAULT_SIGNAL));
    final Duration timeout = Duration.ofSeconds(seconds(config));
    return waits
        .await(executionId, nodeId, name, timeout)
        .onErrorMap(
            java.util.concurrent.TimeoutException.class,
            e ->
                new IllegalArgumentException(
                    "No '" + name + "' approval for tool '" + tool + "' within " + timeout, e))
        .flatMap(
            decision ->
                "reject".equalsIgnoreCase(decision.decision())
                    ? Mono.<Void>error(
                        new IllegalArgumentException(
                            "Tool '" + tool + "' was rejected: " + decision.data()))
                    : Mono.<Void>empty());
  }

  /**
   * Checks the options.
   *
   * @param config the node config
   * @throws IllegalArgumentException if an option is malformed
   */
  void validate(final Map<String, Object> config) {
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
