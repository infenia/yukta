// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.SupervisorAssignments.Assignment;
import com.infenia.yukta.service.supervisor.ownership.OwnershipConflictException;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** What a supervisor owns is set as a whole: checked first, then applied, locks honoured. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorAssignmentsTest {

  private final InMemorySupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public String type() {
          return "test";
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
      };

  private final SupervisorService service =
      new SupervisorService(
          store, List.of(factory), mock(SupervisorHost.class), new InMemoryExecutionLogStore());

  private final SupervisorAssignments assignments = new SupervisorAssignments(service);

  private void supervisors(final String... ids) {
    for (final String id : ids) {
      service.saveSupervisor(new Supervisor(id, "test", Map.of(), false)).block();
    }
  }

  private static Assignment owns(final String target) {
    return new Assignment(SupervisorScope.parse(target), true, false);
  }

  @Test
  void sameTargetGetsTheSameIdSoSettingTwiceChangesNothing() {
    supervisors("a");
    final List<SupervisorAttachment> first =
        assignments.set("a", List.of(owns("workflow:s/w")), false).block();
    final List<SupervisorAttachment> second =
        assignments.set("a", List.of(owns("workflow:s/w")), false).block();

    assertThat(first).isEqualTo(second);
    assertThat(first.get(0).id())
        .isEqualTo(SupervisorAssignments.idOf("a", SupervisorScope.parse("workflow:s/w")));
  }

  @Test
  void checkListsEveryProblemAndChangesNothing() {
    supervisors("a", "b");
    assignments.set("b", List.of(owns("workflow:s/w")), false).block();

    StepVerifier.create(
            assignments.check(
                "a",
                List.of(
                    owns("run:s/w"),
                    owns("run:s/w"),
                    new Assignment(
                        new SupervisorScope.Section("w", null, Set.of("group:ci")), true, false),
                    owns("server"))))
        .assertNext(
            issues ->
                assertThat(issues)
                    .extracting(SettingsValidationException.Issue::field)
                    .containsExactly("assignments[0]", "assignments[1]", "assignments[2]"))
        .verifyComplete();
    StepVerifier.create(assignments.of("a")).verifyComplete();
  }

  @Test
  void setRefusesOverlapsAndBadTargetsBeforeChangingAnything() {
    supervisors("a", "b");
    assignments.set("b", List.of(owns("workflow:s/w")), false).block();

    StepVerifier.create(assignments.set("a", List.of(owns("server"), owns("run:s/w")), false))
        .expectError(OwnershipConflictException.class)
        .verify();
    StepVerifier.create(assignments.set("a", List.of(owns("server"), owns("server")), false))
        .expectError(SettingsValidationException.class)
        .verify();
    StepVerifier.create(assignments.of("a")).verifyComplete();
    StepVerifier.create(assignments.set("nobody", List.of(), false))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void onlyNamedLockHoldersMayUnlockWhenTheListIsSet() {
    supervisors("a");
    assignments.setProperties(
        YuktaSettings.bind(Map.of("yukta.supervisor.lock-holders", "release-admin")));
    assignments
        .set("a", List.of(new Assignment(SupervisorScope.parse("server"), true, true)), false)
        .block();

    StepVerifier.create(
            assignments
                .set("a", List.of(), true)
                .contextWrite(Context.of(ControlActor.CONTEXT_KEY, "key:ops")))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(assignments.of("a")).expectNextCount(1).verifyComplete();
    // a locked assignment cannot be detached around the lock either
    StepVerifier.create(service.detach(SupervisorAssignments.idOf("a", new SupervisorScope.Server())))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(
            assignments
                .set("a", List.of(), true)
                .contextWrite(Context.of(ControlActor.CONTEXT_KEY, "key:release-admin")))
        .assertNext(left -> assertThat(left).isEmpty())
        .verifyComplete();
  }

  @Test
  void keepingLockedAssignmentAsItIsNeedsNoUnlock() {
    supervisors("a");
    final Assignment locked = new Assignment(SupervisorScope.parse("server"), true, true);
    assignments.set("a", List.of(locked), false).block();

    StepVerifier.create(assignments.set("a", List.of(locked, owns("run:s/w")), false))
        .assertNext(all -> assertThat(all).hasSize(2))
        .verifyComplete();
  }
}
