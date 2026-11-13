// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.util.ConfigValidation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an {@code AGENT_SESSION} node's configuration says: its schema, checks and parsed options.
 */
@SuppressWarnings("PMD.TooManyMethods")
final class AgentSessionConfig {

  /* default */ static final String HARNESS = "harness";
  /* default */ static final String PROMPT = "prompt";
  /* default */ static final String MODEL = "model";
  /* default */ static final String MAX_TURNS = "maxTurns";
  /* default */ static final String ARGS = "args";
  /* default */ static final String SESSION = "session";
  /* default */ static final String WORKING_DIR = "workingDir";
  /* default */ static final String ENV = "env";
  /* default */ static final int DEFAULT_CONCURRENCY = 2;
  private static final String ON_BUSY = "onBusy";
  private static final String APPROVALS = "approvals";
  private static final String APPROVAL_RISK = "approvalRisk";
  private static final String APPROVAL_TIMEOUT = "approvalTimeout";
  private static final String APPROVAL_AUDIENCE = "approvalAudience";
  private static final String TIMEOUT = "timeout";
  private static final String WAIT = "WAIT";
  private static final String FAIL = "FAIL";
  private static final String NONE = "NONE";
  private static final String ASK = "ASK";
  private static final long DEFAULT_APPROVAL_SECONDS = 300;
  private static final long DEFAULT_TIMEOUT_SECONDS = 600;
  private static final long MARGIN_SECONDS = 30;

  private final AgentHarnesses harnesses;

  /* default */ AgentSessionConfig(final AgentHarnesses harnesses) {
    this.harnesses = harnesses;
  }

  /* default */ List<ConfigField> schema() {
    return List.of(
        ConfigField.choice(
            HARNESS,
            true,
            null,
            "Which agent harness to run",
            harnesses.all().stream().map(AgentPreset::id).toArray(String[]::new)),
        ConfigField.required(PROMPT, ConfigField.Type.EXPRESSION, "What to ask the agent"),
        ConfigField.optional(
            MODEL, ConfigField.Type.STRING, null, "Model, if the harness takes one"),
        ConfigField.optional(
            MAX_TURNS, ConfigField.Type.NUMBER, null, "Most turns, if the harness takes a limit"),
        ConfigField.optional(
            ARGS, ConfigField.Type.LIST, null, "Extra command-line arguments for the harness"),
        ConfigField.optional(
            SESSION,
            ConfigField.Type.EXPRESSION,
            null,
            "Session key: nodes and messages with the same key continue one conversation (needs a"
                + " harness with resume). Without it every message starts afresh."),
        ConfigField.choice(
            ON_BUSY,
            false,
            WAIT,
            "A session runs one turn at a time. WAIT queues the next turn; FAIL sends it to the"
                + " failure port at once.",
            WAIT,
            FAIL),
        ConfigField.choice(
            APPROVALS,
            false,
            NONE,
            "ASK: each tool use the harness needs permission for becomes an approval request on"
                + " the input request system (answered by a person, a supervisor or an agent;"
                + " no answer in time refuses the tool). NONE: the harness decides alone (see"
                + " args). Needs a harness with the ask capability.",
            NONE,
            ASK),
        ConfigField.choice(
            APPROVAL_RISK,
            false,
            "MEDIUM",
            "Risk of the approval requests",
            "LOW",
            "MEDIUM",
            "HIGH"),
        ConfigField.optional(
            APPROVAL_TIMEOUT,
            ConfigField.Type.NUMBER,
            DEFAULT_APPROVAL_SECONDS,
            "Seconds to wait for an answer before the tool is refused"),
        ConfigField.optional(
            APPROVAL_AUDIENCE,
            ConfigField.Type.LIST,
            null,
            "Who may answer: supervisor:<id>, role:OPERATOR, key:<name>, ... (default: the current"
                + " tier)"),
        ConfigField.optional(WORKING_DIR, ConfigField.Type.STRING, null, "Working directory"),
        ConfigField.optional(ENV, ConfigField.Type.MAP, null, "Extra environment variables"),
        ConfigField.optional(
            TIMEOUT, ConfigField.Type.NUMBER, DEFAULT_TIMEOUT_SECONDS, "Timeout in seconds"),
        ConfigField.optional(
            ConfigValidation.CONCURRENCY_KEY,
            ConfigField.Type.NUMBER,
            DEFAULT_CONCURRENCY,
            "Messages handled at once (each starts an agent)"));
  }

  /** The harness descriptions, one line each, for the plugin's usage text. */
  /* default */ String describeHarnesses() {
    return harnesses.all().stream()
        .map(
            h ->
                "- "
                    + h.id()
                    + ": "
                    + h.description()
                    + " Capabilities: "
                    + h.capabilities().stream().sorted().toList()
                    + "\n")
        .collect(java.util.stream.Collectors.joining());
  }

  /**
   * Checks the configuration.
   *
   * @param config the node configuration
   * @throws IllegalArgumentException when an option is missing, wrong or not supported by the
   *     harness
   */
  /* default */ void validate(final Map<String, Object> config) {
    final AgentPreset preset = preset(config);
    if (config.get(PROMPT) == null) {
      throw new IllegalArgumentException("prompt is mandatory");
    }
    refuse(
        config.get(MODEL) != null && preset.modelArgs().isEmpty(),
        preset,
        "does not take a model",
        MODEL);
    refuse(
        config.get(MAX_TURNS) != null && preset.maxTurnsArgs().isEmpty(),
        preset,
        "has no turn limit",
        MAX_TURNS);
    refuse(
        config.get(SESSION) != null && preset.resumeArgs().isEmpty(),
        preset,
        "cannot resume a session",
        SESSION);
    refuse(
        asks(config) && preset.interactive() == null,
        preset,
        "cannot ask for approvals",
        APPROVALS);
    validateValues(config);
  }

  private void validateValues(final Map<String, Object> config) {
    waitsWhenBusy(config);
    settings(config);
    if (config.get(ARGS) != null && !(config.get(ARGS) instanceof List)) {
      throw new IllegalArgumentException("args must be a list of strings");
    }
    timeoutSeconds(config);
    ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY);
  }

  private static void refuse(
      final boolean unsupported, final AgentPreset preset, final String why, final String option) {
    if (unsupported) {
      throw new IllegalArgumentException(
          "harness '" + preset.id() + "' " + why + "; remove " + option);
    }
  }

  /* default */ AgentPreset preset(final Map<String, Object> config) {
    final Object id = config.get(HARNESS);
    if (id == null) {
      throw new IllegalArgumentException("harness is mandatory; one of " + harnessIds());
    }
    final AgentPreset preset = harnesses.get(String.valueOf(id));
    if (preset == null) {
      throw new IllegalArgumentException("unknown harness '" + id + "'; one of " + harnessIds());
    }
    return preset;
  }

  private List<String> harnessIds() {
    return harnesses.all().stream().map(AgentPreset::id).toList();
  }

  /** Whether each tool use is approved through the input request system. */
  /* default */ static boolean asks(final Map<String, Object> config) {
    return ASK.equals(choice(config, APPROVALS, NONE, ASK));
  }

  /** Whether a second turn of a busy session waits (true) or fails at once (false). */
  /* default */ static boolean waitsWhenBusy(final Map<String, Object> config) {
    return WAIT.equals(choice(config, ON_BUSY, WAIT, FAIL));
  }

  private static String choice(
      final Map<String, Object> config, final String key, final String first, final String second) {
    final String value = String.valueOf(config.getOrDefault(key, first)).toUpperCase(Locale.ROOT);
    if (!first.equals(value) && !second.equals(value)) {
      throw new IllegalArgumentException(
          key + " must be " + first + " or " + second + ", got: " + value);
    }
    return value;
  }

  /* default */ static AgentApprovals.Settings settings(final Map<String, Object> config) {
    final Object timeout = config.get(APPROVAL_TIMEOUT);
    if (timeout != null && !(timeout instanceof final Number n && n.longValue() > 0)) {
      throw new IllegalArgumentException("approvalTimeout must be positive, got: " + timeout);
    }
    return new AgentApprovals.Settings(
        InputAsk.risk(config.get(APPROVAL_RISK), InputRisk.MEDIUM),
        Duration.ofSeconds(
            timeout == null ? DEFAULT_APPROVAL_SECONDS : ((Number) timeout).longValue()),
        InputAsk.audience(config.get(APPROVAL_AUDIENCE)));
  }

  /* default */ static long timeoutSeconds(final Map<String, Object> config) {
    final Object value = config.get(TIMEOUT);
    if (value == null) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
    if (value instanceof final Number n && n.longValue() > 0) {
      return n.longValue();
    }
    throw new IllegalArgumentException("timeout must be positive, got: " + value);
  }

  /** The default step timeout: the process timeout plus room to start and stop. */
  /* default */ static Duration stepTimeout(final Map<String, Object> config) {
    final long seconds = timeoutSeconds(config);
    return Duration.ofSeconds(seconds + Math.max(MARGIN_SECONDS, seconds / 10));
  }

  /* default */ static List<String> command(
      final List<String> base,
      final AgentPreset preset,
      final Map<String, Object> config,
      final AgentSessionPlugin.Turn turn,
      final String token) {
    final List<String> command = new ArrayList<>();
    base.forEach(a -> command.add(a.replace("{prompt}", turn.prompt())));
    if (token != null && !token.isBlank()) {
      preset.resumeArgs().forEach(a -> command.add(a.replace("{session}", token)));
    }
    if (!turn.model().isEmpty()) {
      preset.modelArgs().forEach(a -> command.add(a.replace("{model}", turn.model())));
    }
    if (config.get(MAX_TURNS) != null) {
      final String max = String.valueOf(config.get(MAX_TURNS));
      preset.maxTurnsArgs().forEach(a -> command.add(a.replace("{maxTurns}", max)));
    }
    if (config.get(ARGS) instanceof final List<?> extra) {
      extra.forEach(a -> command.add(String.valueOf(a)));
    }
    return command;
  }
}
