// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Names the exit a run took. A workflow with several exits ends each one in an outcome terminal; a
 * supervisor reads {@code outcome}, {@code restartFrom} and {@code reason} from the terminal's
 * status event and decides what happens next, such as restarting from a node.
 */
@Slf4j
@Component
public final class OutcomeTerminal implements TerminalPlugin {

  /** Config key of the outcome. */
  private static final String OUTCOME_KEY = "outcome";

  /** Config key of the node to restart from. */
  private static final String RESTART_FROM_KEY = "restartFrom";

  /** Config key of the reason. */
  private static final String REASON_KEY = "reason";

  /** The outcomes a run can report. */
  private static final Set<String> OUTCOMES = Set.of("SUCCESS", "RETRY", "ESCALATE", "FAILED");

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Names the exit a run took, for a supervisor to act on.",
        "Put one at the end of each exit of a workflow. Configure with:\n"
            + "- outcome: SUCCESS (all done), RETRY (the cause was repaired, run again),"
            + " ESCALATE (needs a fixer) or FAILED (give up).\n"
            + "- restartFrom: for RETRY and ESCALATE, the node to restart from; omit to restart"
            + " from the start.\n"
            + "- reason: free text for the supervisor and the log.\n"
            + "The outcome is added to the metadata of this node's SUCCESS status event, only when"
            + " the run reached the node.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            OUTCOME_KEY, true, null, "How the run ended", "SUCCESS", "RETRY", "ESCALATE", "FAILED"),
        ConfigField.optional(
            RESTART_FROM_KEY,
            ConfigField.Type.STRING,
            null,
            "Node to restart from; omit for start"),
        ConfigField.optional(REASON_KEY, ConfigField.Type.STRING, null, "Why the run ended here"));
  }

  @Override
  public String getType() {
    return "OUTCOME_TERMINAL";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> outcome(config));
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String outcome = outcome(config);
    return input
        .count()
        .doOnNext(
            count -> {
              if (count > 0) {
                log.atInfo().addKeyValue(OUTCOME_KEY, outcome).log("Run ended with an outcome");
              }
            })
        .then();
  }

  @Override
  public Map<String, Object> completionMetadata(final Map<String, Object> config) {
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put(OUTCOME_KEY, outcome(config));
    for (final String key : List.of(RESTART_FROM_KEY, REASON_KEY)) {
      final Object value = config.get(key);
      if (value != null && !value.toString().isBlank()) {
        metadata.put(key, value.toString());
      }
    }
    return metadata;
  }

  private static String outcome(final Map<String, Object> config) {
    final Object raw = config.get(OUTCOME_KEY);
    final String outcome = raw == null ? "" : raw.toString().strip().toUpperCase(Locale.ROOT);
    if (!OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException(
          "outcome must be one of " + new java.util.TreeSet<>(OUTCOMES));
    }
    return outcome;
  }
}
