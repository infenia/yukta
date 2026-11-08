// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorResultMapper;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ends the run of a supervisor workflow with its decision: {@code {"commands": [...]}} in the
 * message it receives. Unlike {@code OUTCOME_TERMINAL}, a command that the contract cannot read
 * fails the run, so a mistake in the workflow shows up in the run instead of being dropped without
 * a word. The supervisor still checks every command it takes.
 */
@Component
@NoArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class SupervisorDecisionTerminal implements TerminalPlugin {

  private static final String OUTCOME_KEY = "outcome";
  private static final String COMMANDS_KEY = "commands";
  private static final int MAX_COMMANDS = 10;

  private final OutcomeTerminal outcome = new OutcomeTerminal();

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Ends a supervisor workflow with its decision.",
        "Put one at the end of each exit of a workflow that decides for a supervisor. The message"
            + " it receives holds {\"commands\": [{\"type\": \"PauseWorkflow\", \"executionId\":"
            + " ...}]}; every command must be one the supervisor contract reads, or the run fails."
            + " Configure with outcome (default SUCCESS: send the commands; ESCALATE hands the"
            + " request up; RETRY runs once more; FAILED means no opinion), restartFrom and"
            + " reason.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return outcome.getConfigSchema().stream()
        .map(
            field ->
                OUTCOME_KEY.equals(field.name())
                    ? ConfigField.choice(
                        OUTCOME_KEY,
                        false,
                        "SUCCESS",
                        "How the run ended",
                        "SUCCESS",
                        "RETRY",
                        "ESCALATE",
                        "FAILED")
                    : field)
        .toList();
  }

  @Override
  public String getType() {
    return "SUPERVISOR_DECISION";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return outcome.validateConfig(withOutcome(config));
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    return outcome.consume(input.doOnNext(SupervisorDecisionTerminal::check), withOutcome(config));
  }

  @Override
  public Map<String, Object> completionMetadata(final Map<String, Object> config) {
    return outcome.completionMetadata(withOutcome(config));
  }

  private static Map<String, Object> withOutcome(final Map<String, Object> config) {
    final Map<String, Object> out = new HashMap<>(config);
    out.putIfAbsent(OUTCOME_KEY, "SUCCESS");
    return out;
  }

  /** Fails on a command list the supervisor contract cannot read whole. */
  private static void check(final Message<?> message) {
    if (!(message.payload() instanceof Map<?, ?> payload)
        || !(payload.get(COMMANDS_KEY) instanceof List<?> commands)) {
      return;
    }
    if (commands.size() > MAX_COMMANDS) {
      throw new IllegalArgumentException(
          "A decision holds at most " + MAX_COMMANDS + " commands, not " + commands.size());
    }
    final SupervisorResultMapper.Mapped mapped =
        SupervisorResultMapper.map(
            List.of(Map.of(COMMANDS_KEY, commands)),
            Set.copyOf(SupervisorCapabilities.commandNames()),
            // a command without an executionId acts on the run of the signal, which the supervisor
            // fills in; a placeholder stands for it here
            "signal-run",
            MAX_COMMANDS);
    if (mapped.dropped() > 0) {
      throw new IllegalArgumentException(
          mapped.dropped()
              + " of "
              + commands.size()
              + " commands are not valid: each needs a known type and its fields (see the"
              + " supervisor contract)");
    }
  }
}
