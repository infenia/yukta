// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.InMemorySupervisorStore;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The catalog endpoints. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorTypesControllerTest {

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public String type() {
          return "demo-control-plane";
        }

        @Override
        public SupervisorDescriptor describe() {
          return new SupervisorDescriptor(
              "Demo",
              "Does demos.",
              "Use in tests.",
              SupervisorCategory.GUARD,
              Optional.of(new UiDesign("blue", "bolt", "Demo", 140, 80)),
              Map.of("url", "http://x"));
        }

        @Override
        public List<ConfigField> getConfigSchema() {
          return List.of(ConfigField.required("url", ConfigField.Type.STRING, "where"));
        }

        @Override
        public Set<String> defaultAllowedCommands() {
          return Set.of("PauseWorkflowCommand");
        }

        @Override
        public ExecutionSupervisor create(final Supervisor supervisor) {
          throw new IllegalArgumentException("needs a model");
        }
      };

  private final InMemorySupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorService service =
      new SupervisorService(
          store, List.of(factory), mock(SupervisorHost.class), new InMemoryExecutionLogStore());

  private final SupervisorTypesController controller = new SupervisorTypesController(service);

  @Test
  void listShowsTheCatalogEntries() {
    StepVerifier.create(controller.list())
        .assertNext(
            r -> {
              assertThat(r.data()).hasSize(1);
              final var entry = r.data().get(0);
              assertThat(entry.type()).isEqualTo("demo-control-plane");
              assertThat(entry.displayName()).isEqualTo("Demo");
              assertThat(entry.category()).isEqualTo("GUARD");
              assertThat(entry.icon()).isEqualTo("bolt");
              // the example cannot be made, so the kind comes from the name
              assertThat(entry.kind()).isEqualTo("CONTROL_PLANE");
            })
        .verifyComplete();
  }

  @Test
  void detailsCarrySchemasDefaultsAndExample() {
    StepVerifier.create(controller.get("demo-control-plane"))
        .assertNext(
            r -> {
              final var d = r.data();
              assertThat(d.configSchema()).extracting(ConfigField::name).containsExactly("url");
              assertThat(d.commonSchema())
                  .extracting(ConfigField::name)
                  .contains("allowedCommands", "canAnswer", "maxRisk", "observeInternal");
              assertThat(d.defaultAllowedCommands()).containsExactly("PauseWorkflowCommand");
              assertThat(d.exampleSettings()).isEqualTo(Map.of("url", "http://x"));
              assertThat(d.usagePattern()).isEqualTo("Use in tests.");
            })
        .verifyComplete();
  }

  @Test
  void unknownTypeIsNotFound() {
    StepVerifier.create(controller.get("nope")).expectError(NotFoundException.class).verify();
    StepVerifier.create(controller.usage("nope")).expectError(NotFoundException.class).verify();
  }

  @Test
  void usageListsTheSupervisorsOfTheType() {
    StepVerifier.create(
            store.saveSupervisor(
                new Supervisor("s1", "demo-control-plane", Map.of("url", "u"), false)))
        .verifyComplete();
    StepVerifier.create(store.saveSupervisor(new Supervisor("s2", "other", Map.of(), false)))
        .verifyComplete();

    StepVerifier.create(controller.usage("demo-control-plane"))
        .assertNext(r -> assertThat(r.data()).containsExactly("s1"))
        .verifyComplete();
  }

  @Test
  void factoryWithoutAnyCommandRestrictionReportsNull() {
    final SupervisorFactory plain =
        new SupervisorFactory() {
          @Override
          public List<ConfigField> getConfigSchema() {
            return List.of();
          }

          @Override
          public String type() {
            return "plain";
          }

          @Override
          public ExecutionSupervisor create(final Supervisor supervisor) {
            return new ExecutionSupervisor() {
              @Override
              @SuppressWarnings("PMD.ShortMethodName")
              public String id() {
                return "p";
              }

              @Override
              public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
                return Flux.empty();
              }
            };
          }
        };
    final SupervisorTypesController other =
        new SupervisorTypesController(
            new SupervisorService(
                store,
                List.of(plain),
                mock(SupervisorHost.class),
                new InMemoryExecutionLogStore()));

    StepVerifier.create(other.get("plain"))
        .assertNext(
            r -> {
              assertThat(r.data().defaultAllowedCommands()).isNull();
              assertThat(r.data().kind()).isEqualTo("SUPERVISOR");
            })
        .verifyComplete();
  }
}
