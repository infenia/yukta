// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.process;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorResultMapper;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Reads one line a program wrote as a directive. A line is a JSON object with a {@code type}:
 * {@code command} ({@code key}, {@code cause}, {@code reason}, {@code ifStatus}, {@code command}
 * with its own {@code type} and {@code executionId}), {@code verdict} ({@code answers}, {@code
 * verdict}, {@code reason}), {@code noOpinion} ({@code answers}) or {@code delegationAnswer}
 * ({@code answers}, {@code answer}). Anything else is refused.
 */
@UtilityClass
public class DirectiveJson {

  private static final Set<String> VERDICTS = Set.of("ALLOW", "DENY", "REQUIRE_APPROVAL");

  /**
   * Reads a directive.
   *
   * @param line the parsed JSON object
   * @return the directive, or empty when the line is not one
   */
  public static Optional<Directive> read(final Map<String, Object> line) {
    try {
      return Optional.ofNullable(build(line));
    } catch (final IllegalArgumentException | ClassCastException e) {
      return Optional.empty();
    }
  }

  private static @Nullable Directive build(final Map<String, Object> line) {
    final String type = text(line, "type");
    return switch (type == null ? "" : type) {
      case "command" -> command(line);
      case "verdict" -> {
        final String verdict = upper(text(line, "verdict"));
        yield verdict != null && VERDICTS.contains(verdict)
            ? new Directive.ReviewVerdict(
                required(line, "answers"), Directive.Verdict.valueOf(verdict), text(line, "reason"))
            : null;
      }
      case "noOpinion" -> new Directive.NoOpinion(required(line, "answers"));
      case "delegationAnswer" ->
          line.get("answer") instanceof Map<?, ?> answer
              ? new Directive.DelegationAnswer(required(line, "answers"), copy(answer))
              : null;
      default -> null;
    };
  }

  private static @Nullable Directive command(final Map<String, Object> line) {
    if (!(line.get("command") instanceof Map<?, ?> raw)) {
      return null;
    }
    final Map<String, Object> command = copy(raw);
    if (!(command.get("executionId") instanceof String run) || run.isBlank()) {
      return null;
    }
    final List<ExecutionControlCommand> mapped =
        SupervisorResultMapper.map(
                List.of(Map.of("commands", List.of(command))),
                Set.copyOf(SupervisorCapabilities.commandNames()),
                run,
                1)
            .commands();
    return mapped.isEmpty()
        ? null
        : new Directive.Command(
            required(line, "key"),
            mapped.get(0),
            cause(text(line, "cause")),
            text(line, "reason"),
            text(line, "ifStatus"));
  }

  private static Cause cause(final @Nullable String text) {
    if (text == null || "initiative".equals(text)) {
      return Cause.initiative();
    }
    if ("tick".equals(text)) {
      return Cause.tick();
    }
    if (text.startsWith("event:")) {
      return Cause.event(text.substring("event:".length()));
    }
    if (text.startsWith("answers:")) {
      return Cause.answers(text.substring("answers:".length()));
    }
    throw new IllegalArgumentException("not a cause: " + text);
  }

  private static String required(final Map<String, Object> line, final String key) {
    final String value = text(line, key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is missing");
    }
    return value;
  }

  private static @Nullable String text(final Map<String, Object> line, final String key) {
    return line.get(key) instanceof String value ? value : null;
  }

  private static @Nullable String upper(final @Nullable String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static Map<String, Object> copy(final Map<?, ?> raw) {
    final Map<String, Object> out = new java.util.LinkedHashMap<>();
    raw.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }
}
