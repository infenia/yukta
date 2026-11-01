// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** What a profile lets its supervisor do is read when the profile is saved and when it starts. */
@NoArgsConstructor
class SupervisorServiceCapabilitiesTest {

  private final SupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public String type() {
          return "test";
        }

        @Override
        public Set<String> defaultAllowedCommands() {
          return Set.of("PauseWorkflowCommand");
        }

        @Override
        public ExecutionSupervisor create(final SupervisorProfile profile) {
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
    StepVerifier.create(service.saveProfile(new SupervisorProfile("p", "test", settings, false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(new SupervisorBinding("guard", "p", new SupervisorScope.Global(), true)))
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
            service.saveProfile(
                new SupervisorProfile(
                    "p", "test", Map.of("allowedCommands", List.of("NoSuchCommand")), false)))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NoSuchCommand"))
        .verify();

    StepVerifier.create(store.profiles()).verifyComplete();
  }

  @Test
  void findsAnAttachedSupervisorByItsBindingId() {
    final AttachedSupervisors attached = new AttachedSupervisors();
    final BoundSupervisor bound =
        new BoundSupervisor(
            new SupervisorBinding("guard", "p", new SupervisorScope.Global(), true),
            mock(ExecutionSupervisor.class),
            false);
    attached.put(bound);

    assertThat(attached.find("guard")).isEqualTo(Optional.of(bound));
    assertThat(attached.find("other")).isEmpty();
  }
}
