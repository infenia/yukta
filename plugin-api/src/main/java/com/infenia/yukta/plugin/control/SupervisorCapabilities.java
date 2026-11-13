// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.input.InputRisk;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a supervisor made from a {@link Supervisor} is allowed to do, read from the profile's
 * settings. The engine enforces it; a supervisor does not decide it.
 *
 * @param allowedCommands the command types it may send, by simple name (for example {@code
 *     PauseWorkflowCommand}); {@code null} means every command, which is how supervisors have
 *     always worked
 * @param canAnswer whether it may answer input requests at all
 * @param maxRisk the highest risk of a request it may answer
 * @param operatorAccess the command types that people may send through it (it reviews them); empty
 *     means none, so a person cannot control what it owns
 * @param initiative the command types it may send with no triggering signal (a tick or its own
 *     initiative), a subset of {@code allowedCommands}; empty means none
 * @param maxInitiativePerMinute how many such commands it may send per minute
 * @since 0.1.0-alpha.4
 */
public record SupervisorCapabilities(
    @Nullable Set<String> allowedCommands,
    boolean canAnswer,
    InputRisk maxRisk,
    Set<String> operatorAccess,
    Set<String> initiative,
    int maxInitiativePerMinute) {

  /** Setting: command types the supervisor may send with no triggering signal. */
  public static final String INITIATIVE = "initiative";

  /** Setting: how many commands without a triggering signal per minute. */
  public static final String MAX_INITIATIVE_PER_MINUTE = "maxInitiativePerMinute";

  /** Setting: command types that people may send through the supervisor. */
  public static final String OPERATOR_ACCESS = "operatorAccess";

  /** Setting: list of command names the supervisor may send. */
  public static final String ALLOWED_COMMANDS = "allowedCommands";

  /** Setting: whether the supervisor may answer input requests. */
  public static final String CAN_ANSWER = "canAnswer";

  /** Setting: the highest risk of a request the supervisor may answer. */
  public static final String MAX_RISK = "maxRisk";

  /** Setting: whether the supervisor sees the events of internal sessions its scope names. */
  public static final String OBSERVE_INTERNAL = "observeInternal";

  /** The fewest commands without a triggering signal a minute that may be allowed. */
  private static final int LEAST_INITIATIVE_PER_MINUTE = 1;

  /** The names of the command types, which an {@code allowedCommands} setting must come from. */
  // Listed by hand: a native image cannot read the permitted subclasses of a sealed interface, so
  // Class#getPermittedSubclasses is empty there. SupervisorCapabilitiesTest checks the list against
  // it on the JVM.
  private static final Set<String> KNOWN_COMMANDS =
      Set.of(
          "PauseWorkflowCommand",
          "ResumeWorkflowCommand",
          "PauseNodeCommand",
          "ResumeNodeCommand",
          "StopNodeCommand",
          "SkipNodeCommand",
          "EnableStepModeCommand",
          "DisableStepModeCommand",
          "StepNodeCommand",
          "StopWorkflowCommand",
          "RestartCommand",
          "RestartFromNodeCommand",
          "SetBreakpointCommand",
          "SetLimitsCommand",
          "SetPriorityCommand",
          "AnswerInputCommand",
          "EscalateInputCommand",
          "StartWorkflowCommand",
          "RecoverCommand",
          "DrainCommand");

  /**
   * The names of the command types a supervisor may be allowed to send.
   *
   * @return the names, sorted
   */
  public static List<String> commandNames() {
    return KNOWN_COMMANDS.stream().sorted().toList();
  }

  /**
   * Keeps an immutable copy of the command names.
   *
   * @param allowedCommands the command names, or {@code null} for every command
   * @param canAnswer whether it may answer input requests
   * @param maxRisk the highest risk it may answer
   */
  @SuppressWarnings("PMD.NullAssignment") // null is the documented value for "every command"
  public SupervisorCapabilities {
    allowedCommands = allowedCommands == null ? null : Set.copyOf(allowedCommands);
    Objects.requireNonNull(maxRisk, "maxRisk");
    operatorAccess = operatorAccess == null ? Set.of() : Set.copyOf(operatorAccess);
    initiative = initiative == null ? Set.of() : Set.copyOf(initiative);
    if (maxInitiativePerMinute < LEAST_INITIATIVE_PER_MINUTE) {
      throw new IllegalArgumentException(MAX_INITIATIVE_PER_MINUTE + " must be at least 1");
    }
  }

  /**
   * Capabilities with the people-access list and no initiative.
   *
   * @param allowedCommands the command names, or {@code null} for every command
   * @param canAnswer whether it may answer input requests
   * @param maxRisk the highest risk it may answer
   * @param operatorAccess the command types people may send through it
   */
  public SupervisorCapabilities(
      final @Nullable Set<String> allowedCommands,
      final boolean canAnswer,
      final InputRisk maxRisk,
      final @Nullable Set<String> operatorAccess) {
    this(
        allowedCommands,
        canAnswer,
        maxRisk,
        operatorAccess,
        Set.of(),
        SupervisorContract.DEFAULT_INITIATIVE_PER_MINUTE);
  }

  /**
   * Capabilities that let no person send a command through the supervisor.
   *
   * @param allowedCommands the command names, or {@code null} for every command
   * @param canAnswer whether it may answer input requests
   * @param maxRisk the highest risk it may answer
   */
  public SupervisorCapabilities(
      final @Nullable Set<String> allowedCommands,
      final boolean canAnswer,
      final InputRisk maxRisk) {
    this(allowedCommands, canAnswer, maxRisk, Set.of());
  }

  /**
   * Whether the supervisor may send a command with no triggering signal.
   *
   * @param command the command
   * @return true if its type is in {@code initiative}
   */
  public boolean mayInitiate(final ExecutionControlCommand command) {
    return initiative.contains(command.getClass().getSimpleName());
  }

  /**
   * Whether a person may send a command through the supervisor.
   *
   * @param command the command
   * @return true if its type is listed in {@code operatorAccess}
   */
  public boolean allowsOperator(final ExecutionControlCommand command) {
    return operatorAccess.contains(command.getClass().getSimpleName());
  }

  /**
   * Reads the capabilities from a profile; a missing {@code allowedCommands} means every command.
   *
   * @param profile the profile
   * @return the capabilities
   * @throws IllegalArgumentException if a setting has the wrong type, names an unknown command or
   *     an unknown risk
   */
  public static SupervisorCapabilities from(final Supervisor profile) {
    return from(profile, null);
  }

  /**
   * Reads the capabilities from a profile, with the factory's default for the commands.
   *
   * @param profile the profile
   * @param defaultAllowed the commands to allow when the profile does not say, or {@code null} for
   *     every command; see {@link SupervisorFactory#defaultAllowedCommands()}
   * @return the capabilities
   * @throws IllegalArgumentException if a setting has the wrong type, names an unknown command or
   *     an unknown risk
   */
  public static SupervisorCapabilities from(
      final Supervisor profile, final @Nullable Set<String> defaultAllowed) {
    final Map<String, Object> settings = profile.settings();
    return new SupervisorCapabilities(
        commands(settings.get(ALLOWED_COMMANDS), defaultAllowed),
        flag(settings.get(CAN_ANSWER)),
        risk(settings.get(MAX_RISK)),
        operators(settings.get(OPERATOR_ACCESS)),
        operators(settings.get(INITIATIVE)),
        perMinute(settings.get(MAX_INITIATIVE_PER_MINUTE)));
  }

  private static int perMinute(final @Nullable Object value) {
    if (value == null) {
      return SupervisorContract.DEFAULT_INITIATIVE_PER_MINUTE;
    }
    if (value instanceof final Number number && number.intValue() >= 1) {
      return number.intValue();
    }
    throw new IllegalArgumentException(
        MAX_INITIATIVE_PER_MINUTE + " must be a number of at least 1");
  }

  private static Set<String> operators(final @Nullable Object value) {
    return Objects.requireNonNullElse(commands(value, Set.of()), Set.of());
  }

  /**
   * Whether the supervisor may send a command.
   *
   * @param command the command
   * @return true if every command is allowed or the command's type is listed
   */
  public boolean allows(final ExecutionControlCommand command) {
    return allowedCommands == null || allowedCommands.contains(command.getClass().getSimpleName());
  }

  /**
   * Whether the supervisor may answer a request of a risk.
   *
   * @param risk the risk of the request
   * @return true if it may answer at all and its ceiling is at least {@code risk}
   */
  public boolean mayAnswer(final InputRisk risk) {
    return canAnswer && maxRisk.atLeast(risk);
  }

  private static @Nullable Set<String> commands(
      final @Nullable Object value, final @Nullable Set<String> defaultAllowed) {
    if (value == null) {
      return defaultAllowed;
    }
    if (!(value instanceof final Collection<?> names)) {
      throw new IllegalArgumentException(ALLOWED_COMMANDS + " must be a list of command names");
    }
    final Set<String> allowed = new LinkedHashSet<>();
    for (final Object name : names) {
      if (!(name instanceof final String text) || !KNOWN_COMMANDS.contains(text)) {
        throw new IllegalArgumentException(
            "Unknown command in "
                + ALLOWED_COMMANDS
                + ": '"
                + name
                + "'; known commands: "
                + List.copyOf(KNOWN_COMMANDS).stream().sorted().toList());
      }
      allowed.add(text);
    }
    return allowed;
  }

  private static boolean flag(final @Nullable Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof final Boolean bool) {
      return bool;
    }
    if (value instanceof final String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return Boolean.parseBoolean(text);
    }
    throw new IllegalArgumentException(CAN_ANSWER + " must be true or false");
  }

  private static InputRisk risk(final @Nullable Object value) {
    if (value == null) {
      return InputRisk.LOW;
    }
    if (value instanceof final String text) {
      try {
        return InputRisk.valueOf(text.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // fall through to the clear message below
        throw new IllegalArgumentException(
            MAX_RISK + " must be LOW, MEDIUM or HIGH, not '" + text + "'", e);
      }
    }
    throw new IllegalArgumentException(MAX_RISK + " must be LOW, MEDIUM or HIGH");
  }
}
