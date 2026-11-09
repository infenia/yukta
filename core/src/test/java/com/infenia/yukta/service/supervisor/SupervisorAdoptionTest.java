// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Stored supervisors of every retired type are converted at start-up, or left alone and named. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidInstantiatingObjectsInLoops"})
class SupervisorAdoptionTest {

  /** An engine that adopts some retired types, and fails on settings it cannot read. */
  private static SupervisorFactory engine(final String type, final Set<String> replaces) {
    return new SupervisorFactory() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public List<ConfigField> getConfigSchema() {
        return List.of();
      }

      @Override
      public ExecutionSupervisor create(final Supervisor supervisor) {
        return new ExecutionSupervisor() {
          @Override
          @SuppressWarnings("PMD.ShortMethodName")
          public String id() {
            return supervisor.id();
          }

          @Override
          public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
            return Flux.empty();
          }
        };
      }

      @Override
      public Optional<Adoption> adopt(final Supervisor legacy) {
        if (!replaces.contains(legacy.type())) {
          return Optional.empty();
        }
        if (legacy.settings().containsKey("unreadable")) {
          throw new IllegalArgumentException("cannot read the old settings");
        }
        return Optional.of(
            new Adoption(
                legacy.withEngine(type, Map.of("from", legacy.type())),
                "stall-watchdog".equals(legacy.type())
                    ? List.of("watches whole runs")
                    : List.of()));
      }
    };
  }

  @Test
  void everyRetiredTypeIsConvertedOrLeftStoredAndStartUpGoesOn() {
    final InMemorySupervisorStore store = new InMemorySupervisorStore();
    final List<String> retired =
        List.of(
            "rule-supervisor",
            "rule-control-plane",
            "failure-guard",
            "approval-gate",
            "stall-watchdog",
            "webhook",
            "rules-first-control-plane",
            "model-control-plane",
            "composite");
    for (final String type : retired) {
      store
          .saveSupervisor(new Supervisor("s-" + type, type, Map.of(), true, "old " + type))
          .block();
    }
    store
        .saveSupervisor(new Supervisor("bad", "failure-guard", Map.of("unreadable", 1), false))
        .block();
    store.saveSupervisor(new Supervisor("current", "rules", Map.of(), false)).block();
    final SupervisorService service =
        new SupervisorService(
            store,
            List.of(
                engine(
                    "rules",
                    Set.of(
                        "rule-supervisor",
                        "rule-control-plane",
                        "failure-guard",
                        "approval-gate",
                        "stall-watchdog")),
                engine("callout", Set.of("webhook"))),
            mock(SupervisorHost.class),
            new InMemoryExecutionLogStore());

    service.adoptRetired().block();

    final Map<String, Supervisor> byId = store.supervisors().collectMap(Supervisor::id).block();
    assertThat(byId.get("s-failure-guard").type()).isEqualTo("rules");
    assertThat(byId.get("s-failure-guard").settings()).containsEntry("from", "failure-guard");
    assertThat(byId.get("s-failure-guard").dryRun()).isTrue();
    assertThat(byId.get("s-failure-guard").description()).isEqualTo("old failure-guard");
    assertThat(byId.get("s-webhook").type()).isEqualTo("callout");
    // nobody replaces these: they stay as stored and do not start
    assertThat(
            byId.values().stream()
                .filter(s -> !Set.of("rules", "callout").contains(s.type()))
                .map(Supervisor::id)
                .collect(Collectors.toSet()))
        .containsExactlyInAnyOrder(
            "s-rules-first-control-plane", "s-model-control-plane", "s-composite", "bad");
    assertThat(byId.get("current").settings()).isEmpty();
  }
}
