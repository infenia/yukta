// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.controller.SupervisorsController.AttachmentBody;
import com.infenia.yukta.controller.SupervisorsController.EnabledBody;
import com.infenia.yukta.controller.SupervisorsController.SupervisorBody;
import com.infenia.yukta.dto.response.AttachmentView;
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
import com.infenia.yukta.service.supervisor.SupervisorDecision;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The supervisor endpoints, run against a real service and an in-memory store. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.AvoidInstantiatingObjectsInLoops"
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

  private final SupervisorsController controller = new SupervisorsController(service, host);

  private static SupervisorBody body(final String id, final Map<String, Object> settings) {
    return new SupervisorBody(id, TYPE, "for tests", settings, false, null, null);
  }

  private void created(final String id) {
    StepVerifier.create(controller.create(body(id, Map.of()))).expectNextCount(1).verifyComplete();
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
    StepVerifier.create(controller.attach(new AttachmentBody("a1", "s1", "global", false)))
        .expectNextCount(1)
        .verifyComplete();

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
            0,
            null);
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
    StepVerifier.create(controller.detach("rules")).expectError(ConflictException.class).verify();
    StepVerifier.create(controller.attachments(null, null, "SYSTEM"))
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
  void manualAttachmentsCanBeChangedAndDefinitionOnesCannot() {
    created("s1");
    StepVerifier.create(controller.attach(new AttachmentBody("a1", "s1", "session:A", true)))
        .assertNext(r -> assertThat(r.data().source()).isEqualTo("MANUAL"))
        .verifyComplete();
    StepVerifier.create(controller.patch("a1", new EnabledBody(false)))
        .assertNext(r -> assertThat(r.data().enabled()).isFalse())
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

    StepVerifier.create(controller.patch("def-1", new EnabledBody(false)))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(controller.detach("def-1")).expectError(ConflictException.class).verify();
    StepVerifier.create(controller.attach(new AttachmentBody("def-1", "s1", "global", true)))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(controller.patch("missing", new EnabledBody(true)))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(controller.detach("a1")).expectNextCount(1).verifyComplete();
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
      StepVerifier.create(controller.attach(new AttachmentBody(a[0], "s1", a[1], false)))
          .expectNextCount(1)
          .verifyComplete();
    }

    assertThat(ids(controller.attachments("A", "w1", null)))
        .containsExactlyInAnyOrder("g", "sa", "wa", "wany", "sec");
    assertThat(ids(controller.attachments("A", null, null)))
        .containsExactlyInAnyOrder("g", "sa", "wa", "wother", "sec");
    assertThat(ids(controller.attachments(null, "w1", null)))
        .containsExactlyInAnyOrder("g", "wa", "wany", "sec");
    assertThat(ids(controller.attachments(null, null, "manual"))).hasSize(7);
    assertThat(ids(controller.attachments(null, null, "DEFINITION"))).isEmpty();
    StepVerifier.create(controller.attachmentsOf("s1"))
        .assertNext(r -> assertThat(r.data()).hasSize(7))
        .verifyComplete();
  }

  private static List<String> ids(
      final reactor.core.publisher.Mono<
              com.infenia.yukta.model.api.ApiResponse<List<AttachmentView>>>
          response) {
    return java.util.Objects.requireNonNull(response.block()).data().stream()
        .map(AttachmentView::id)
        .toList();
  }

  @Test
  void decisionsFilterByAttachmentOrSupervisorAndAreCapped() {
    created("s1");
    StepVerifier.create(controller.attach(new AttachmentBody("a1", "s1", "global", false)))
        .expectNextCount(1)
        .verifyComplete();
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
    StepVerifier.create(controller.attach(new AttachmentBody("a1", "s1", "global", false)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(controller.delete("s1")).expectError(ConflictException.class).verify();
    StepVerifier.create(controller.detach("a1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(controller.delete("s1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(controller.delete("s1")).expectError(NotFoundException.class).verify();
  }

  @Test
  void missingRequiredFieldsAreClientErrors() {
    StepVerifier.create(controller.attach(new AttachmentBody("a", null, "global", true)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            controller.create(new SupervisorBody("x", " ", null, null, null, null, null)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  private static SupervisorInfo system(
      final String id, final String scope, final String session, final String workflow) {
    return new SupervisorInfo(
        id, "SUPERVISOR", "T", false, 20, 0, 0, 0, 0, scope, session, workflow, null, 0, null);
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

    StepVerifier.create(controller.attachments(null, null, "SYSTEM"))
        .assertNext(
            r ->
                assertThat(r.data())
                    .extracting(AttachmentView::scope)
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
  void attachDefaultsToEnabledAndAnIdIsGeneratedWhenMissing() {
    created("s1");

    StepVerifier.create(controller.attach(new AttachmentBody(null, "s1", "global", null)))
        .assertNext(
            r -> {
              assertThat(r.data().enabled()).isTrue();
              assertThat(r.data().id()).isNotBlank();
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
      StepVerifier.create(controller.attach(new AttachmentBody(a[0], "s1", a[1], false)))
          .expectNextCount(1)
          .verifyComplete();
    }

    assertThat(ids(controller.attachments(null, "w1", null)))
        .containsExactlyInAnyOrder("w1", "w1b", "any", "sec");
    assertThat(ids(controller.attachments("A", "w1", null)))
        .containsExactlyInAnyOrder("w1", "any", "sec", "se");
    assertThat(ids(controller.attachments("B", "w1", null)))
        .containsExactlyInAnyOrder("w1b", "any");
    assertThat(ids(controller.attachments("B", null, null))).containsExactly("w1b");
    assertThat(ids(controller.attachments(null, "w2", null))).isEmpty();
  }

  @Test
  void serverAttachmentsListedOnlyWithoutFilter() {
    created("s1");
    StepVerifier.create(controller.attach(new AttachmentBody("srv", "s1", "server", false)))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(ids(controller.attachments(null, null, "manual"))).containsExactly("srv");
    assertThat(ids(controller.attachments("A", null, "manual"))).isEmpty();
    assertThat(ids(controller.attachments(null, "w1", "manual"))).isEmpty();
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
            java.util.List.of("boss"),
            Map.of(
                "approval",
                new SupervisorBody.DelegationBody("all", 45L),
                "input",
                new SupervisorBody.DelegationBody(null, null)));

    StepVerifier.create(controller.create(withParents))
        .assertNext(
            r -> {
              org.assertj.core.api.Assertions.assertThat(r.data().parents())
                  .containsExactly("boss");
              org.assertj.core.api.Assertions.assertThat(r.data().delegation())
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
}
