// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The entry of a workflow that decides for a supervisor: it emits the signal the supervisor starts
 * the run with (an execution event, secrets masked). {@code signals} documents which kinds of
 * signal the workflow is made for and is checked against the contract.
 */
@Component
@NoArgsConstructor
public final class SupervisorSignalTrigger implements TriggerPlugin {

  private static final String SIGNALS_KEY = "signals";
  private static final Set<String> KINDS =
      Set.of("EVENT", "TICK", "REVIEW", "DELEGATED", "STARTED");

  /** The refusal of a signal kind that is not one of {@link #KINDS}. */
  private static final String KINDS_MESSAGE =
      "signals must be a comma-separated list of " + new java.util.TreeSet<>(KINDS);

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Starts a workflow with the signal of a supervisor.",
        "Put one at the start of a workflow that decides for a supervisor. It emits the payload"
            + " the supervisor started the run with, as 'default'. signals lists the kinds of"
            + " signal the workflow is made for: EVENT, TICK, REVIEW, DELEGATED, STARTED.",
        Optional.empty());
  }

  @Override
  public String getType() {
    return "SUPERVISOR_SIGNAL";
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.optional(
            SIGNALS_KEY,
            ConfigField.Type.STRING,
            null,
            "Signal kinds this workflow is made for, comma-separated"));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          final Object listed = config.get(SIGNALS_KEY);
          if (listed == null || listed.toString().isBlank()) {
            return;
          }
          for (final String kind : listed.toString().split(",")) {
            if (!KINDS.contains(kind.strip().toUpperCase(java.util.Locale.ROOT))) {
              throw new IllegalArgumentException(KINDS_MESSAGE);
            }
          }
        });
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Map<String, Object> payload = ctx.getOrDefault("payload", Map.of());
          return Flux.just(
              DefaultMessage.create(UUID.randomUUID(), payload).withSourcePort("default"));
        });
  }
}
