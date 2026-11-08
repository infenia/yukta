// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * What a supervisor lets its supervisor do is read when the supervisor is saved and when it starts.
 */
@NoArgsConstructor
class SupervisorServiceCapabilitiesTest {

  /** The supervisor type of the test factories. */
  private static final String TYPE = "test";

  private final SupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public List<ConfigField> getConfigSchema() {
          return List.of();
        }

        @Override
        public String type() {
          return TYPE;
        }

        @Override
        public Set<String> defaultAllowedCommands() {
          return Set.of("PauseWorkflowCommand");
        }

        @Override
        public ExecutionSupervisor create(final Supervisor supervisor) {
          return new ExecutionSupervisor() {
            @Override
            @SuppressWarnings("PMD.ShortMethodName")
            public String id() {
              return "made";
            }

            @Override
            public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
              return Flux.empty();
            }
          };
        }
      };

  private final SupervisorService service =
      new SupervisorService(store, List.of(factory), host, new InMemoryExecutionLogStore());

  private BoundSupervisor attachedWith(final Map<String, Object> settings) {
    StepVerifier.create(service.saveSupervisor(new Supervisor("p", TYPE, settings, false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(
                new SupervisorAttachment("guard", "p", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();
    final ArgumentCaptor<BoundSupervisor> captor = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(captor.capture());
    return captor.getValue();
  }

  @Test
  void theProfilesAnsweringRightsReachTheAttachedSupervisor() {
    final BoundSupervisor bound =
        attachedWith(
            Map.of(
                "canAnswer",
                true,
                "maxRisk",
                "MEDIUM",
                "allowedCommands",
                List.of("StopWorkflowCommand")));

    assertThat(bound.capabilities().canAnswer()).isTrue();
    assertThat(bound.capabilities().maxRisk()).isEqualTo(InputRisk.MEDIUM);
    assertThat(bound.capabilities().allowedCommands()).containsExactly("StopWorkflowCommand");
  }

  @Test
  void profileWithoutAllowedCommandsGetsTheFactorysDefault() {
    final BoundSupervisor bound = attachedWith(Map.of());

    assertThat(bound.capabilities().canAnswer()).isFalse();
    assertThat(bound.capabilities().allowedCommands()).containsExactly("PauseWorkflowCommand");
  }

  @Test
  void anUnknownCommandNameIsRefusedAtSaveAndNothingIsStored() {
    StepVerifier.create(
            service.saveSupervisor(
                new Supervisor(
                    "p", TYPE, Map.of("allowedCommands", List.of("NoSuchCommand")), false)))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(SettingsValidationException.class)
                    .hasMessageContaining("settings.allowedCommands")
                    .hasMessageContaining("NoSuchCommand"))
        .verify();

    StepVerifier.create(store.supervisors()).verifyComplete();
  }

  @Test
  void findsAnAttachedSupervisorByItsBindingId() {
    final AttachedSupervisors attached = new AttachedSupervisors();
    final BoundSupervisor bound =
        Fixtures.boundSupervisor(
            new SupervisorAttachment("guard", "p", new SupervisorScope.Global(), true),
            mock(ExecutionSupervisor.class),
            false);
    attached.put(bound);

    assertThat(attached.find("guard")).isEqualTo(Optional.of(bound));
    assertThat(attached.find("other")).isEmpty();
  }

  /** The test factory with a schema of one field, so other keys are refused. */
  private SupervisorFactory declaring() {
    return new SupervisorFactory() {
      @Override
      public String type() {
        return TYPE;
      }

      @Override
      public List<ConfigField> getConfigSchema() {
        return List.of(ConfigField.optional("known", ConfigField.Type.STRING, null, "a key"));
      }

      @Override
      public ExecutionSupervisor create(final Supervisor supervisor) {
        return factory.create(supervisor);
      }
    };
  }

  @Test
  void settingThatIsNotInTheSchemaIsRefusedWithItsField() {
    final SupervisorService declared =
        new SupervisorService(store, List.of(declaring()), host, new InMemoryExecutionLogStore());
    StepVerifier.create(
            declared.saveSupervisor(new Supervisor("p", TYPE, Map.of("bogus", 1), false)))
        .expectErrorSatisfies(
            error ->
                assertThat(((SettingsValidationException) error).getIssues())
                    .extracting(SettingsValidationException.Issue::field)
                    .containsExactly("settings.bogus"))
        .verify();

    StepVerifier.create(store.supervisors()).verifyComplete();
  }

  @Test
  void storedProfileThatNoLongerFitsItsSchemaIsSkippedOnRestore() {
    final Supervisor bad = new Supervisor("bad", TYPE, Map.of("bogus", 1), false);
    final SupervisorService declared =
        new SupervisorService(store, List.of(declaring()), host, new InMemoryExecutionLogStore());
    StepVerifier.create(
            store
                .saveSupervisor(bad)
                .then(
                    store.saveAttachment(
                        new SupervisorAttachment("b1", "bad", new SupervisorScope.Global(), true))))
        .verifyComplete();

    declared.restore();

    verify(host, Mockito.after(500).never()).attach(ArgumentMatchers.any());
  }
}
