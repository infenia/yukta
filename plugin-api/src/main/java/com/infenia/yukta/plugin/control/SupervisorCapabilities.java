// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.input.InputRisk;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What a supervisor made from a {@link SupervisorProfile} is allowed to do, read from the profile's
 * settings. The engine enforces it; a supervisor does not decide it.
 *
 * @param allowedCommands the command types it may send, by simple name (for example {@code
 *     PauseWorkflowCommand}); {@code null} means every command, which is how supervisors have
 *     always worked
 * @param canAnswer whether it may answer input requests at all
 * @param maxRisk the highest risk of a request it may answer
 * @since 0.1.0-alpha.4
 */
public record SupervisorCapabilities(
    @Nullable Set<String> allowedCommands, boolean canAnswer, InputRisk maxRisk) {

  /** Setting: list of command names the supervisor may send. */
  public static final String ALLOWED_COMMANDS = "allowedCommands";

  /** Setting: whether the supervisor may answer input requests. */
  public static final String CAN_ANSWER = "canAnswer";

  /** Setting: the highest risk of a request the supervisor may answer. */
  public static final String MAX_RISK = "maxRisk";

  /** The names of the command types, which an {@code allowedCommands} setting must come from. */
  private static final Set<String> KNOWN_COMMANDS =
      Arrays.stream(ExecutionControlCommand.class.getPermittedSubclasses())
          .map(Class::getSimpleName)
          .collect(Collectors.toUnmodifiableSet());

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
  }

  /**
   * Reads the capabilities from a profile; a missing {@code allowedCommands} means every command.
   *
   * @param profile the profile
   * @return the capabilities
   * @throws IllegalArgumentException if a setting has the wrong type, names an unknown command or
   *     an unknown risk
   */
  public static SupervisorCapabilities from(final SupervisorProfile profile) {
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
      final SupervisorProfile profile, final @Nullable Set<String> defaultAllowed) {
    final Map<String, Object> settings = profile.settings();
    return new SupervisorCapabilities(
        commands(settings.get(ALLOWED_COMMANDS), defaultAllowed),
        flag(settings.get(CAN_ANSWER)),
        risk(settings.get(MAX_RISK)));
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
