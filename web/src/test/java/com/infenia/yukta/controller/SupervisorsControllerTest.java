// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.controller.SupervisorsController.AssignmentBody;
import com.infenia.yukta.controller.SupervisorsController.SupervisorBody;
import com.infenia.yukta.dto.response.AssignmentView;
import com.infenia.yukta.dto.response.SupervisorValidation;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.InMemorySupervisorStore;
import com.infenia.yukta.service.supervisor.SettingsValidationException;
import com.infenia.yukta.service.supervisor.SupervisorAssignments;
import com.infenia.yukta.service.supervisor.SupervisorDecision;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.supervisor.ownership.OwnershipConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The supervisor endpoints, run against a real service and an in-memory store. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.UseConcurrentHashMap"
})
class SupervisorsControllerTest {

  private static final String TYPE = "test";

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final InMemorySupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public String type() {
          return TYPE;
        }

        @Override
        public List<ConfigField> getConfigSchema() {
          return List.of(
              ConfigField.optional("url", ConfigField.Type.STRING, null, "where"),
              ConfigField.optional("bearer", ConfigField.Type.SECRET, null, "credential"));
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

  private final SupervisorsController controller =
      new SupervisorsController(service, host, new SupervisorAssignments(service));

  /** Attaches straight through the service, as definitions do; the endpoint sets assignments. */
  private Mono<SupervisorAttachment> attach(
      final String id, final String supervisor, final String scope, final Boolean enabled) {
    return service.attach(
        new SupervisorAttachment(
            id, supervisor, SupervisorScope.parse(scope), enabled == null || enabled));
  }

  private static SupervisorBody body(final String id, final Map<String, Object> settings) {
    return new SupervisorBody(id, TYPE, "for tests", settings, false, null, null);
  }

  private void created(final String id) {
    StepVerifier.create(controller.create(body(id, Map.of()))).expectNextCount(1).verifyComplete();
  }

  @Test
  void versionsAreListedWithSecretsHiddenAndRollbackRestoresOne() {
    StepVerifier.create(controller.create(body("s1", Map.of("bearer", "abc", "url", "u1"))))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(controller.replace("s1", body("s1", Map.of("bearer", "***", "url", "u2"))))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(controller.versions("s1"))
        .assertNext(
            r -> {
              assertThat(r.data()).hasSize(2);
              assertThat(r.data().get(0).version()).isEqualTo(2);
              assertThat(r.data().get(0).supervisor().settings().get("bearer")).isEqualTo("***");
            })
        .verifyComplete();
    StepVerifier.create(controller.rollback("s1", 1))
        .assertNext(r -> assertThat(r.data().settings().get("url")).isEqualTo("u1"))
        .verifyComplete();
  }

  @Test
  void replayOfAnUnknownRunIsNotFound() {
    StepVerifier.create(controller.create(body("s1", Map.of("url", "u"))))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(controller.replay("s1", new SupervisorsController.ReplayBody("gone", null)))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void replayDraftIsKeptAsUnmodifiableCopy() {
    final Map<String, Object> draft = new java.util.HashMap<>();
    draft.put("url", null);
    final SupervisorsController.ReplayBody body = new SupervisorsController.ReplayBody("e", draft);
    draft.put("other", "x");
    final Map<String, Object> kept = java.util.Objects.requireNonNull(body.settings());
    assertThat(kept).containsOnlyKeys("url");
    assertThatThrownBy(() -> kept.put("y", 1)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void systemSupervisorCannotBeRolledBack() {
    when(host.isBuiltIn("sys")).thenReturn(true);
    StepVerifier.create(controller.rollback("sys", 1))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void createdSupervisorIsListedWithItsCountsAndSecretsHidden() {
    StepVerifier.create(controller.create(body("s1", Map.of("bearer", "abc", "url", "u"))))
        .assertNext(
            r -> {
              assertThat(r.status()).isEqualTo(201);
              assertThat(r.data().settings()).isEqualTo(Map.of("bearer", "***", "url", "u"));
              assertThat(r.data().description()).isEqualTo("for tests");
            })
        .verifyComplete();
    StepVerifier.create(attach("a1", "s1", "global", false)).expectNextCount(1).verifyComplete();

    StepVerifier.create(controller.list())
        .assertNext(
            r -> {
              assertThat(r.data()).hasSize(1);
              assertThat(r.data().get(0).attachments()).isEqualTo(1);
              assertThat(r.data().get(0).system()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void blankIdIsGeneratedAndAnExistingIdConflicts() {
    StepVerifier.create(controller.create(body("  ", Map.of())))
        .assertNext(r -> assertThat(r.data().id()).isNotBlank())
        .verifyComplete();
    created("s1");

    StepVerifier.create(controller.create(body("s1", Map.of())))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void unknownKeyIsRefusedWithItsField() {
    StepVerifier.create(controller.create(body("s1", Map.of("bogus", 1))))
        .expectErrorSatisfies(
            e ->
                assertThat(((SettingsValidationException) e).getIssues())
                    .extracting(SettingsValidationException.Issue::field)
                    .containsExactly("settings.bogus"))
        .verify();
  }

  @Test
  void replacingKeepsStoredSecretBehindTheMarker() {
    StepVerifier.create(controller.create(body("s1", Map.of("bearer", "abc"))))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            controller.replace("s1", body("ignored", Map.of("bearer", "***", "url", "n"))))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(store.supervisors())
        .assertNext(s -> assertThat(s.settings()).isEqualTo(Map.of("bearer", "abc", "url", "n")))
        .verifyComplete();
  }

  @Test
  void returnsOneOrNotFound() {
    created("s1");

    StepVerifier.create(controller.get("s1"))
        .assertNext(r -> assertThat(r.data().id()).isEqualTo("s1"))
        .verifyComplete();
    StepVerifier.create(controller.get("nope")).expectError(NotFoundException.class).verify();
  }

  @Test
  void systemSupervisorsAreListedReadOnly() {
    final SupervisorInfo info =
        new SupervisorInfo(
            "rules",
            "SUPERVISOR",
            "RuleSupervisor",
            false,
            20,
            3,
            1,
            0,
            0,
            "GLOBAL",
            null,
            null,
            null,
            0);
    when(host.status()).thenReturn(List.of(info));
    when(host.isBuiltIn("rules")).thenReturn(true);

    StepVerifier.create(controller.list())
        .assertNext(
            r -> {
              assertThat(r.data()).hasSize(1);
              assertThat(r.data().get(0).system()).isTrue();
              assertThat(r.data().get(0).applied()).isEqualTo(3);
            })
        .verifyComplete();
    StepVerifier.create(controller.replace("rules", body("rules", Map.of())))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(controller.delete("rules")).expectError(ConflictException.class).verify();
    StepVerifier.create(controller.setAssignments("rules", false, List.of()))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(controller.assignments(null, null, "SYSTEM"))
        .assertNext(
            r -> {
              assertThat(r.data()).hasSize(1);
              assertThat(r.data().get(0).source()).isEqualTo("SYSTEM");
            })
        .verifyComplete();
  }

  @Test
  void validateReportsProblemsByFieldWithoutSaving() {
    StepVerifier.create(controller.validate(body("s1", Map.of("bogus", 1))))
        .assertNext(
            r -> {
              assertThat(r.data().valid()).isFalse();
              assertThat(r.data().errors())
                  .extracting(SupervisorValidation.FieldIssue::field)
                  .containsExactly("settings.bogus");
            })
        .verifyComplete();
    StepVerifier.create(controller.validate(body(null, Map.of())))
        .assertNext(r -> assertThat(r.data().valid()).isTrue())
        .verifyComplete();
    StepVerifier.create(store.supervisors()).verifyComplete();
  }

  @Test
  void assignmentsAreSetAsWholeAndDefinitionOnesStay() {
    created("s1");
    StepVerifier.create(
            controller.setAssignments(
                "s1", false, List.of(new AssignmentBody("session:A", true, null))))
        .assertNext(
            r -> {
              assertThat(r.data())
                  .singleElement()
                  .extracting(AssignmentView::source)
                  .isEqualTo("MANUAL");
              assertThat(r.data().get(0).id()).startsWith(SupervisorAssignments.ID_PREFIX);
            })
        .verifyComplete();
    StepVerifier.create(
            controller.setAssignments(
                "s1", false, List.of(new AssignmentBody("session:A", false, null))))
        .assertNext(r -> assertThat(r.data().get(0).enabled()).isFalse())
        .verifyComplete();
    StepVerifier.create(
            store.saveAttachment(
                new SupervisorAttachment(
                    "def-1",
                    "s1",
                    new SupervisorScope.Session("B"),
                    true,
                    SupervisorSource.DEFINITION,
                    "B",
                    null)))
        .verifyComplete();

    // an empty list removes the assignments made here; the definition's stays
    StepVerifier.create(controller.setAssignments("s1", false, List.of()))
        .assertNext(
            r -> assertThat(r.data()).extracting(AssignmentView::id).containsExactly("def-1"))
        .verifyComplete();
  }

  @Test
  void targetAnotherSupervisorOwnsIsRefusedAndCheckSaysWhyWithoutSaving() {
    created("s1");
    created("s2");
    controller
        .setAssignments("s1", false, List.of(new AssignmentBody("workflow:A/w1", true, null)))
        .block();

    StepVerifier.create(
            controller.checkAssignments(
                "s2", List.of(new AssignmentBody("workflow:A/w1//build", true, null))))
        .assertNext(
            r -> {
              assertThat(r.data().valid()).isFalse();
              assertThat(r.data().problems())
                  .singleElement()
                  .asString()
                  .startsWith("assignments[0]");
            })
        .verifyComplete();
    StepVerifier.create(
            controller.setAssignments(
                "s2", false, List.of(new AssignmentBody("workflow:A/w1//build", true, null))))
        .expectError(OwnershipConflictException.class)
        .verify();
    StepVerifier.create(controller.assignmentsOf("s2"))
        .assertNext(r -> assertThat(r.data()).isEmpty())
        .verifyComplete();
  }

  @Test
  void lockedAssignmentChangesOnlyWhenTheRequestAsksToUnlock() {
    created("s1");
    controller
        .setAssignments("s1", false, List.of(new AssignmentBody("session:A", true, true)))
        .block();

    StepVerifier.create(controller.setAssignments("s1", false, List.of()))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(controller.setAssignments("s1", true, List.of()))
        .assertNext(r -> assertThat(r.data()).isEmpty())
        .verifyComplete();
  }

  @Test
  void effectiveSupervisorsOfSessionAndWorkflowFollowTheScopes() {
    created("s1");
    for (final String[] a :
        new String[][] {
          {"g", "global"},
          {"sa", "session:A"},
          {"sb", "session:B"},
          {"wa", "workflow:A/w1"},
          {"wany", "workflow:w1"},
          {"wother", "workflow:A/w2"},
          {"sec", "workflow:A/w1//build.*"}
        }) {
      StepVerifier.create(attach(a[0], "s1", a[1], false)).expectNextCount(1).verifyComplete();
    }

    assertThat(ids(controller.assignments("A", "w1", null)))
        .containsExactlyInAnyOrder("g", "sa", "wa", "wany", "sec");
    assertThat(ids(controller.assignments("A", null, null)))
        .containsExactlyInAnyOrder("g", "sa", "wa", "wother", "sec");
    assertThat(ids(controller.assignments(null, "w1", null)))
        .containsExactlyInAnyOrder("g", "wa", "wany", "sec");
    assertThat(ids(controller.assignments(null, null, "manual"))).hasSize(7);
    assertThat(ids(controller.assignments(null, null, "DEFINITION"))).isEmpty();
    StepVerifier.create(controller.assignmentsOf("s1"))
        .assertNext(r -> assertThat(r.data()).hasSize(7))
        .verifyComplete();
  }

  private static List<String> ids(
      final reactor.core.publisher.Mono<
              com.infenia.yukta.model.api.ApiResponse<List<AssignmentView>>>
          response) {
    return java.util.Objects.requireNonNull(response.block()).data().stream()
        .map(AssignmentView::id)
        .toList();
  }

  @Test
  void decisionsFilterByAttachmentOrSupervisorAndAreCapped() {
    created("s1");
    StepVerifier.create(attach("a1", "s1", "global", false)).expectNextCount(1).verifyComplete();
    final Instant now = Instant.now();
    final SupervisorDecision older =
        new SupervisorDecision(
            now.minusSeconds(5), "a1", "applied", "PauseWorkflowCommand", "e1", null);
    final SupervisorDecision newer =
        new SupervisorDecision(now, "s1", "rejected", "StopWorkflowCommand", "e2", "no");
    when(host.decisions("a1", 50)).thenReturn(List.of(older));
    when(host.decisions("s1", 50)).thenReturn(List.of(newer));
    when(host.decisions(null, 500)).thenReturn(List.of(newer, older));

    StepVerifier.create(controller.decisions(null, "a1", 50))
        .assertNext(r -> assertThat(r.data()).containsExactly(older))
        .verifyComplete();
    StepVerifier.create(controller.decisions("s1", null, 50))
        .assertNext(r -> assertThat(r.data()).containsExactly(newer, older))
        .verifyComplete();
    StepVerifier.create(controller.decisions(null, null, 9999))
        .assertNext(r -> assertThat(r.data()).hasSize(2))
        .verifyComplete();
  }

  @Test
  void statusIsWhatTheHostReports() {
    when(host.status()).thenReturn(List.of());

    StepVerifier.create(controller.status())
        .assertNext(r -> assertThat(r.data()).isEmpty())
        .verifyComplete();
  }

  @Test
  void deletingSupervisorThatIsStillAttachedConflicts() {
    created("s1");
    StepVerifier.create(attach("a1", "s1", "global", false)).expectNextCount(1).verifyComplete();

    StepVerifier.create(controller.delete("s1")).expectError(ConflictException.class).verify();
    StepVerifier.create(controller.setAssignments("s1", false, List.of()))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(controller.delete("s1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(controller.delete("s1")).expectError(NotFoundException.class).verify();
  }

  @Test
  void missingRequiredFieldsAreClientErrors() {
    StepVerifier.create(
            controller.setAssignments("s1", false, List.of(new AssignmentBody(null, true, null))))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            controller.checkAssignments(
                "nobody", List.of(new AssignmentBody("server", true, null))))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(
            controller.create(new SupervisorBody("x", " ", null, null, null, null, null)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  private static SupervisorInfo system(
      final String id, final String scope, final String session, final String workflow) {
    return new SupervisorInfo(
        id, "SUPERVISOR", "T", false, 20, 0, 0, 0, 0, scope, session, workflow, null, 0);
  }

  @Test
  void systemAttachmentsShowTheirScopeKeys() {
    when(host.status())
        .thenReturn(
            List.of(
                system("g", "GLOBAL", null, null),
                system("se", "SESSION", "A", null),
                system("wf", "WORKFLOW", "A", "w"),
                system("wa", "WORKFLOW", null, "w"),
                system("sc", "SECTION", "A", "w")));
    when(host.isBuiltIn(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

    StepVerifier.create(controller.assignments(null, null, "SYSTEM"))
        .assertNext(
            r ->
                assertThat(r.data())
                    .extracting(AssignmentView::scope)
                    .containsExactly(
                        "global", "session:A", "workflow:A/w", "workflow:w", "workflow:A/w"))
        .verifyComplete();
  }

  @Test
  void systemSupervisorIdCannotBeTakenByNewOne() {
    when(host.isBuiltIn("rules")).thenReturn(true);

    StepVerifier.create(controller.create(body("rules", Map.of())))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void validateTakesBlankIdAsDraftAndKeepsStoredSecretsOfExistingOne() {
    StepVerifier.create(controller.create(body("s1", Map.of("bearer", "abc"))))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(controller.validate(body("  ", Map.of())))
        .assertNext(r -> assertThat(r.data().valid()).isTrue())
        .verifyComplete();
    StepVerifier.create(controller.validate(body("s1", Map.of("bearer", "***"))))
        .assertNext(r -> assertThat(r.data().valid()).isTrue())
        .verifyComplete();
  }

  @Test
  void assignmentDefaultsToEnabledAndUnlocked() {
    created("s1");

    StepVerifier.create(
            controller.setAssignments(
                "s1", false, List.of(new AssignmentBody("server", null, null))))
        .assertNext(
            r -> {
              assertThat(r.data().get(0).enabled()).isTrue();
              assertThat(r.data().get(0).locked()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void workflowScopesFollowTheQueriedSessionAndWorkflow() {
    created("s1");
    for (final String[] a :
        new String[][] {
          {"w1", "workflow:A/w1"},
          {"w1b", "workflow:B/w1"},
          {"any", "workflow:w1"},
          {"sec", "workflow:A/w1//x"},
          {"se", "session:A"}
        }) {
      StepVerifier.create(attach(a[0], "s1", a[1], false)).expectNextCount(1).verifyComplete();
    }

    assertThat(ids(controller.assignments(null, "w1", null)))
        .containsExactlyInAnyOrder("w1", "w1b", "any", "sec");
    assertThat(ids(controller.assignments("A", "w1", null)))
        .containsExactlyInAnyOrder("w1", "any", "sec", "se");
    assertThat(ids(controller.assignments("B", "w1", null)))
        .containsExactlyInAnyOrder("w1b", "any");
    assertThat(ids(controller.assignments("B", null, null))).containsExactly("w1b");
    assertThat(ids(controller.assignments(null, "w2", null))).isEmpty();
  }

  @Test
  void serverAttachmentsListedOnlyWithoutFilter() {
    created("s1");
    StepVerifier.create(attach("srv", "s1", "server", false)).expectNextCount(1).verifyComplete();

    assertThat(ids(controller.assignments(null, null, "manual"))).containsExactly("srv");
    assertThat(ids(controller.assignments("A", null, "manual"))).isEmpty();
    assertThat(ids(controller.assignments(null, "w1", "manual"))).isEmpty();
  }

  @Test
  void parentsAndDelegationModesAreReadFromTheBodyAndShownInTheView() {
    controller.create(body("boss", Map.of())).block();
    final SupervisorBody withParents =
        new SupervisorBody(
            "child",
            TYPE,
            "reports up",
            Map.of(),
            false,
            List.of("boss"),
            Map.of(
                "approval",
                new SupervisorBody.DelegationBody("all", 45L),
                "input",
                new SupervisorBody.DelegationBody(null, null)));

    StepVerifier.create(controller.create(withParents))
        .assertNext(
            r -> {
              assertThat(r.data().parents()).containsExactly("boss");
              assertThat(r.data().delegation())
                  .containsEntry(
                      "APPROVAL",
                      new com.infenia.yukta.dto.response.SupervisorView.DelegationView("ALL", 45))
                  .containsEntry(
                      "INPUT",
                      new com.infenia.yukta.dto.response.SupervisorView.DelegationView(
                          "ORDERED", 30));
            })
        .verifyComplete();

    StepVerifier.create(
            controller.create(
                new SupervisorBody(
                    "bad",
                    TYPE,
                    null,
                    Map.of(),
                    false,
                    null,
                    Map.of("nonsense", new SupervisorBody.DelegationBody("ALL", 1L)))))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void aSystemSupervisorIsShownByIdAndAnUnknownOneIsNotFound() {
    when(host.status()).thenReturn(List.of(system("rules", "GLOBAL", null, null)));
    when(host.isBuiltIn("rules")).thenReturn(true);

    StepVerifier.create(controller.get("rules"))
        .assertNext(r -> assertThat(r.data().system()).isTrue())
        .verifyComplete();
    StepVerifier.create(controller.get("ghost")).expectError(NotFoundException.class).verify();
  }

  @Test
  void twoRunningSupervisorsWithOneIdAreShownOnce() {
    when(host.status())
        .thenReturn(
            List.of(system("dup", "SESSION", "A", null), system("dup", "SESSION", "B", null)));
    created("dup");

    StepVerifier.create(controller.get("dup"))
        .assertNext(r -> assertThat(r.data().id()).isEqualTo("dup"))
        .verifyComplete();
  }

  @Test
  void checkingNothingToOwnIsValidAndAMissingListIsAClientError() {
    created("s1");

    StepVerifier.create(controller.checkAssignments("s1", List.of()))
        .assertNext(
            r -> {
              assertThat(r.data().valid()).isTrue();
              assertThat(r.message()).isEqualTo("No problems");
            })
        .verifyComplete();
    StepVerifier.create(controller.checkAssignments("s1", null))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void aReplayThatFindsDecisionsAnswersWithThem() {
    created("s1");
    final SupervisorService spied = org.mockito.Mockito.spy(service);
    org.mockito.Mockito.doReturn(
            Mono.just(
                List.of(
                    new SupervisorService.ReplayDecision(
                        "PauseWorkflowCommand", "e1", null, "event:e1", "ok", true))))
        .when(spied)
        .replay("s1", null, "e1");
    final SupervisorsController replaying =
        new SupervisorsController(spied, host, new SupervisorAssignments(spied));

    StepVerifier.create(replaying.replay("s1", new SupervisorsController.ReplayBody("e1", null)))
        .assertNext(r -> assertThat(r.data()).hasSize(1))
        .verifyComplete();
  }

  @Test
  void aMissingIdIsGeneratedAndAMissingTypeIsAClientError() {
    StepVerifier.create(controller.create(body(null, Map.of())))
        .assertNext(r -> assertThat(r.data().id()).isNotBlank())
        .verifyComplete();
    StepVerifier.create(
            controller.create(new SupervisorBody("x", null, null, null, null, null, null)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
