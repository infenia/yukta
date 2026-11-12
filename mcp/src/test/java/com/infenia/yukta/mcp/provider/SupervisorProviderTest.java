// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.SupervisorListing;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.supervisor.OwnershipMap;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link SupervisorProvider}. */
@NoArgsConstructor
class SupervisorProviderTest {

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorService service = mock(SupervisorService.class);

  private final com.infenia.yukta.service.supervisor.agent.AgentCredentials credentials =
      new com.infenia.yukta.service.supervisor.agent.AgentCredentials();

  private final OwnershipMap ownership = mock(OwnershipMap.class);

  private final com.infenia.yukta.service.supervisor.SupervisorAssignments assignments =
      mock(com.infenia.yukta.service.supervisor.SupervisorAssignments.class);

  private final com.infenia.yukta.service.review.ControlReviewer reviewer =
      mock(com.infenia.yukta.service.review.ControlReviewer.class);

  private final com.infenia.yukta.service.control.gateway.ControlBusGateway bus =
      mock(com.infenia.yukta.service.control.gateway.ControlBusGateway.class);

  private final com.infenia.yukta.service.supervisor.SupervisionSummary summary =
      mock(com.infenia.yukta.service.supervisor.SupervisionSummary.class);

  private final SupervisorProvider provider =
      new SupervisorProvider(
          host, service, credentials, ownership, assignments, reviewer, bus, summary);

  @BeforeEach
  void noAttachmentsYet() {
    when(service.attachments()).thenReturn(Flux.empty());
  }

  @Test
  void listShowsRunningSupervisorsBindingsAndTypes() {
    when(host.status())
        .thenReturn(
            List.of(
                new SupervisorInfo(
                    "b1",
                    "SUPERVISOR",
                    "T",
                    true,
                    20,
                    3,
                    1,
                    0,
                    0,
                    "SECTION",
                    "A",
                    "w",
                    List.of("build.*"),
                    0)));
    when(service.attachments())
        .thenReturn(
            Flux.just(new SupervisorAttachment("b1", "p", new SupervisorScope.Session("A"), true)));
    when(service.supervisors())
        .thenReturn(Flux.just(new Supervisor("p", "rule-supervisor", java.util.Map.of(), false)));
    when(service.types()).thenReturn(List.of("rule-supervisor"));

    StepVerifier.create(provider.list())
        .assertNext(
            listing -> {
              assertThat(listing.running())
                  .singleElement()
                  .satisfies(
                      r -> {
                        assertThat(r.id()).isEqualTo("b1");
                        assertThat(r.scope()).isEqualTo("SECTION");
                        assertThat(r.scopeSessionId()).isEqualTo("A");
                        assertThat(r.scopeNodes()).containsExactly("build.*");
                        assertThat(r.applied()).isEqualTo(3);
                        assertThat(r.dryRun()).isTrue();
                      });
              assertThat(listing.assignments())
                  .singleElement()
                  .satisfies(b -> assertThat(b.scope()).isEqualTo("session:A"));
              assertThat(listing.supervisors())
                  .singleElement()
                  .satisfies(c -> assertThat(c.type()).isEqualTo("rule-supervisor"));
              assertThat(listing.types()).containsExactly("rule-supervisor");
            })
        .verifyComplete();
  }

  @Test
  void runningSupervisorWithoutSectionHasNoNodesAndKeepsCopyOtherwise() {
    final List<String> nodes = new ArrayList<>(List.of("a"));
    final SupervisorListing.RunningSupervisor section =
        new SupervisorListing.RunningSupervisor(
            "s", "SUPERVISOR", "SECTION", null, "w", nodes, false, 0, 0);
    nodes.add("b");

    assertThat(section.scopeNodes()).containsExactly("a");
    assertThat(
            new SupervisorListing.RunningSupervisor(
                    "g", "SUPERVISOR", "GLOBAL", null, null, null, false, 0, 0)
                .scopeNodes())
        .isNull();
  }

  @Test
  void saveBuildsSupervisorAndDoesNotEchoItsSettings() {
    when(service.saveSupervisor(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.save(" s1 ", "t", java.util.Map.of("k", 1), true, " "))
        .assertNext(
            saved -> {
              assertThat(saved.id()).isEqualTo("s1");
              assertThat(saved.dryRun()).isTrue();
              assertThat(saved.description()).isNull();
            })
        .verifyComplete();
    StepVerifier.create(provider.save(null, "t", null, false, "why"))
        .assertNext(saved -> assertThat(saved.id()).isNotBlank())
        .verifyComplete();
  }

  @Test
  void saveRefusesTheHiddenMarkerSoSecretIsNeverOverwrittenByIt() {
    StepVerifier.create(provider.save("s1", "t", java.util.Map.of("apiKey", "***"), false, null))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void catalogDescribesTypesAndRefusesAnUnknownOne() {
    final com.infenia.yukta.plugin.control.SupervisorDescriptor descriptor =
        new com.infenia.yukta.plugin.control.SupervisorDescriptor(
            "T",
            "does t",
            "use t",
            com.infenia.yukta.plugin.control.SupervisorCategory.RULES,
            java.util.Optional.empty(),
            java.util.Map.of("k", 1));
    when(service.types()).thenReturn(List.of("t"));
    when(service.describe("t")).thenReturn(java.util.Optional.of(descriptor));
    when(service.describe("nope")).thenReturn(java.util.Optional.empty());
    when(service.configSchema("t")).thenReturn(List.of());
    when(service.defaultAllowedCommands("t")).thenReturn(java.util.Set.of("B", "A"));

    assertThat(provider.types().types())
        .singleElement()
        .satisfies(e -> assertThat(e.displayName()).isEqualTo("T"));
    final var details = provider.type("t");
    assertThat(details.defaultAllowedCommands()).containsExactly("A", "B");
    assertThat(details.commonSchema()).isNotEmpty();
    assertThat(details.exampleSettings()).containsEntry("k", 1);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.type("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void saveKeepsGivenDescriptionAndSettings() {
    when(service.saveSupervisor(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.save("s1", "t", java.util.Map.of("k", 1), false, "pauses"))
        .assertNext(saved -> assertThat(saved.description()).isEqualTo("pauses"))
        .verifyComplete();
    final ArgumentCaptor<Supervisor> sent = ArgumentCaptor.forClass(Supervisor.class);
    verify(service).saveSupervisor(sent.capture());
    assertThat(sent.getValue().settings()).containsEntry("k", 1);
  }

  @Test
  void catalogEntryWithoutDefaultCommandRestrictionHasNone() {
    final com.infenia.yukta.plugin.control.SupervisorDescriptor descriptor =
        new com.infenia.yukta.plugin.control.SupervisorDescriptor(
            "T",
            "d",
            "u",
            com.infenia.yukta.plugin.control.SupervisorCategory.RULES,
            java.util.Optional.empty(),
            java.util.Map.of());
    when(service.describe("t")).thenReturn(java.util.Optional.of(descriptor));
    when(service.configSchema("t")).thenReturn(List.of());
    when(service.defaultAllowedCommands("t")).thenReturn(null);

    assertThat(provider.type("t").defaultAllowedCommands()).isNull();
  }

  @Test
  void saveGeneratesIdForBlankOneAndTakesNoDescription() {
    when(service.saveSupervisor(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.save("  ", "t", java.util.Map.of(), false, null))
        .assertNext(
            saved -> {
              assertThat(saved.id()).isNotBlank();
              assertThat(saved.description()).isNull();
            })
        .verifyComplete();
  }

  @Test
  void contractNamesItsVersionAndOperations() {
    final var contract = provider.contract();
    org.assertj.core.api.Assertions.assertThat(contract.version()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(contract.operations()).isNotEmpty();
  }

  @Test
  void presetsOfAnUnknownTypeAreRefusedAndKnownOnesListed() {
    when(service.presets("nope")).thenReturn(java.util.Optional.empty());
    when(service.presets("t"))
        .thenReturn(
            java.util.Optional.of(
                java.util.List.of(
                    new com.infenia.yukta.plugin.control.SupervisorPreset(
                        "p", "does p", java.util.Map.of("k", 1)))));

    reactor.test.StepVerifier.create(provider.presets("t"))
        .assertNext(
            list -> {
              org.assertj.core.api.Assertions.assertThat(list.presets()).hasSize(1);
              org.assertj.core.api.Assertions.assertThat(list.presets().get(0).name())
                  .isEqualTo("p");
            })
        .verifyComplete();
    reactor.test.StepVerifier.create(provider.presets("nope"))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void versionsShowNamesOfSettingsNeverTheirValues() {
    final var saved =
        new Supervisor("s1", "t", java.util.Map.of("bearer", "topsecret", "url", "u"), false);
    when(service.versions("s1"))
        .thenReturn(
            Mono.just(
                java.util.List.of(
                    new com.infenia.yukta.service.supervisor.SupervisorVersions.Version(
                        2, java.time.Instant.EPOCH, saved))));

    reactor.test.StepVerifier.create(provider.versions("s1"))
        .assertNext(
            list -> {
              final var version = list.versions().get(0);
              org.assertj.core.api.Assertions.assertThat(version.version()).isEqualTo(2);
              org.assertj.core.api.Assertions.assertThat(version.settingKeys())
                  .containsExactly("bearer", "url");
              org.assertj.core.api.Assertions.assertThat(list.toString())
                  .doesNotContain("topsecret");
            })
        .verifyComplete();
  }

  @Test
  void rollbackAndReplayAnswerFromTheService() {
    when(service.rollback("s1", 1))
        .thenReturn(Mono.just(new Supervisor("s1", "t", java.util.Map.of(), true, "d")));
    when(service.replay("s1", null, "run1"))
        .thenReturn(
            Mono.just(
                java.util.List.of(
                    new SupervisorService.ReplayDecision(
                        "PauseWorkflowCommand", "run1", null, "tick", "why", false))));

    reactor.test.StepVerifier.create(provider.rollback("s1", 1))
        .assertNext(saved -> org.assertj.core.api.Assertions.assertThat(saved.dryRun()).isTrue())
        .verifyComplete();
    reactor.test.StepVerifier.create(provider.replay("s1", "run1", null))
        .assertNext(
            replay -> {
              org.assertj.core.api.Assertions.assertThat(replay.decisions()).hasSize(1);
              org.assertj.core.api.Assertions.assertThat(replay.decisions().get(0).allowed())
                  .isFalse();
            })
        .verifyComplete();
  }

  @Test
  void credentialIsMadeForAnHttpSupervisorOnlyAndRevoked() {
    when(service.supervisors())
        .thenReturn(
            Flux.just(
                new Supervisor("agent", "http", java.util.Map.of(), false),
                new Supervisor("rules", "rule-supervisor", java.util.Map.of(), false)));

    final String[] token = new String[1];
    reactor.test.StepVerifier.create(provider.issueCredential("agent", 2))
        .assertNext(
            issued -> {
              token[0] = issued.token();
              org.assertj.core.api.Assertions.assertThat(issued.expiresAt()).isNotNull();
            })
        .verifyComplete();
    org.assertj.core.api.Assertions.assertThat(credentials.authenticate(token[0]))
        .contains("agent");
    reactor.test.StepVerifier.create(provider.issueCredential("agent", null))
        .assertNext(
            issued -> org.assertj.core.api.Assertions.assertThat(issued.expiresAt()).isNull())
        .verifyComplete();
    reactor.test.StepVerifier.create(provider.issueCredential("rules", null))
        .expectError(ConflictException.class)
        .verify();
    reactor.test.StepVerifier.create(provider.issueCredential("ghost", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    reactor.test.StepVerifier.create(provider.issueCredential("agent", 0))
        .expectError(IllegalArgumentException.class)
        .verify();

    reactor.test.StepVerifier.create(provider.revokeCredential("agent"))
        .expectNextCount(1)
        .verifyComplete();
    reactor.test.StepVerifier.create(provider.revokeCredential("agent"))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void ownershipIsTheMapsAnswer() {
    when(ownership.ownersOf("s", "w"))
        .thenReturn(
            Mono.just(
                new OwnershipMap.Ownership(
                    "s", "w", true, "guard", java.util.Map.of("a", "default"))));
    reactor.test.StepVerifier.create(provider.ownership("s", "w"))
        .assertNext(
            found -> {
              org.assertj.core.api.Assertions.assertThat(found.run()).isEqualTo("guard");
              org.assertj.core.api.Assertions.assertThat(found.nodes())
                  .containsEntry("a", "default");
            })
        .verifyComplete();
  }

  @Test
  void assignmentsAreReadSetAndCheckedThroughTheAssignments() {
    final SupervisorAttachment owned =
        new SupervisorAttachment("asg-1", "p", new SupervisorScope.Server(), true).withLocked(true);
    when(assignments.of("p")).thenReturn(Flux.just(owned));
    when(assignments.set(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(Mono.just(List.of(owned)));
    when(assignments.check(any(), any()))
        .thenReturn(
            Mono.just(
                List.of(
                    new com.infenia.yukta.service.supervisor.SettingsValidationException.Issue(
                        "assignments[0]", "taken"))));
    final List<com.infenia.yukta.mcp.dto.AssignmentInput> wanted =
        List.of(new com.infenia.yukta.mcp.dto.AssignmentInput("server", null, true));

    StepVerifier.create(provider.assignments("p"))
        .assertNext(r -> assertThat(r.assignments()).singleElement().matches(a -> a.locked()))
        .verifyComplete();
    StepVerifier.create(provider.setAssignments("p", wanted, true))
        .assertNext(r -> assertThat(r.assignments()).hasSize(1))
        .verifyComplete();
    StepVerifier.create(provider.checkAssignments("p", wanted))
        .assertNext(
            r -> {
              assertThat(r.valid()).isFalse();
              assertThat(r.problems()).containsExactly("assignments[0]: taken");
            })
        .verifyComplete();
    final ArgumentCaptor<
            List<com.infenia.yukta.service.supervisor.SupervisorAssignments.Assignment>>
        sent = ArgumentCaptor.captor();
    verify(assignments)
        .set(
            org.mockito.ArgumentMatchers.eq("p"),
            sent.capture(),
            org.mockito.ArgumentMatchers.eq(true));
    assertThat(sent.getValue().get(0).enabled()).isTrue();
    assertThat(sent.getValue().get(0).locked()).isTrue();
  }

  @Test
  void assignmentsNeedListAndTargets() {
    StepVerifier.create(provider.setAssignments("p", null, false))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            provider.checkAssignments(
                "p", List.of(new com.infenia.yukta.mcp.dto.AssignmentInput(" ", true, null))))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void detailsHideSecretsAndDeleteGoesToTheService() {
    when(service.supervisors())
        .thenReturn(
            Flux.just(new Supervisor("p", "T", Map.of("apiKey", "s3cret", "level", 2), false)));
    when(service.configSchema("T")).thenReturn(List.of());
    when(assignments.of("p")).thenReturn(Flux.empty());
    when(service.deleteSupervisor("p")).thenReturn(Mono.empty());

    StepVerifier.create(provider.details("p"))
        .assertNext(
            d -> {
              assertThat(d.settings()).containsEntry("apiKey", "***").containsEntry("level", 2);
              assertThat(d.assignments()).isEmpty();
            })
        .verifyComplete();
    StepVerifier.create(provider.details("nobody"))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.delete("p"))
        .assertNext(r -> assertThat(r.deleted()).isTrue())
        .verifyComplete();
  }

  @Test
  void supervisionAsksTheReviewerForTheRunOfAnExecution() {
    final com.infenia.yukta.model.execution.WorkflowProgress progress =
        mock(com.infenia.yukta.model.execution.WorkflowProgress.class);
    when(progress.sessionId()).thenReturn("s");
    when(progress.workflowId()).thenReturn("w");
    when(bus.getCurrentProgress("e1")).thenReturn(progress);
    when(reviewer.capabilities("s", "w", null))
        .thenReturn(
            Map.of(
                "pause",
                new com.infenia.yukta.service.review.ControlReviewer.Capability(
                    false, "guard", "guard controls this")));
    when(reviewer.capabilities(null, null, null)).thenReturn(Map.of());

    StepVerifier.create(provider.supervision(null, null, "e1", null))
        .assertNext(
            a ->
                assertThat(a.commands())
                    .singleElement()
                    .satisfies(
                        c -> {
                          assertThat(c.command()).isEqualTo("pause");
                          assertThat(c.via()).isEqualTo("guard");
                          assertThat(c.allowed()).isFalse();
                        }))
        .verifyComplete();
    StepVerifier.create(provider.supervision(null, null, "unknown", null))
        .assertNext(a -> assertThat(a.commands()).isEmpty())
        .verifyComplete();
  }

  @Test
  void secretFieldOfTheSchemaIsHiddenWhateverItsName() {
    when(service.supervisors())
        .thenReturn(Flux.just(new Supervisor("p", "T", Map.of("passphrase", "s3cret"), false)));
    when(service.configSchema("T"))
        .thenReturn(
            List.of(
                com.infenia.yukta.plugin.core.ConfigField.optional(
                    "passphrase",
                    com.infenia.yukta.plugin.core.ConfigField.Type.SECRET,
                    null,
                    "a secret"),
                com.infenia.yukta.plugin.core.ConfigField.optional(
                    "level",
                    com.infenia.yukta.plugin.core.ConfigField.Type.NUMBER,
                    null,
                    "plain")));
    when(assignments.of("p")).thenReturn(Flux.empty());

    StepVerifier.create(provider.details("p"))
        .assertNext(d -> assertThat(d.settings()).containsEntry("passphrase", "***"))
        .verifyComplete();
  }

  @Test
  void supervisionOfNodeNamedWithoutAnExecutionUsesTheSessionAndWorkflowGiven() {
    when(reviewer.capabilities("s", "w", "n")).thenReturn(Map.of());

    StepVerifier.create(provider.supervision("s", "w", null, "n"))
        .assertNext(a -> assertThat(a.commands()).isEmpty())
        .verifyComplete();
  }

  @Test
  void anAssignmentWithNoTargetOrSwitchedOffAndUnlockedIsReadAsGiven() {
    when(assignments.set(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(Mono.just(List.of()));
    final ArgumentCaptor<
            List<com.infenia.yukta.service.supervisor.SupervisorAssignments.Assignment>>
        sent = ArgumentCaptor.captor();

    StepVerifier.create(
            provider.setAssignments(
                "p",
                List.of(new com.infenia.yukta.mcp.dto.AssignmentInput("server", false, false)),
                null))
        .expectNextCount(1)
        .verifyComplete();
    verify(assignments)
        .set(
            org.mockito.ArgumentMatchers.eq("p"),
            sent.capture(),
            org.mockito.ArgumentMatchers.eq(false));
    assertThat(sent.getValue().get(0).enabled()).isFalse();
    assertThat(sent.getValue().get(0).locked()).isFalse();

    StepVerifier.create(
            provider.setAssignments(
                "p",
                List.of(new com.infenia.yukta.mcp.dto.AssignmentInput(null, true, true)),
                false))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void anAssignmentSwitchedOnExplicitlyIsReadAsOn() {
    when(assignments.set(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(Mono.just(List.of()));
    final ArgumentCaptor<
            List<com.infenia.yukta.service.supervisor.SupervisorAssignments.Assignment>>
        sent = ArgumentCaptor.captor();

    StepVerifier.create(
            provider.setAssignments(
                "p",
                List.of(new com.infenia.yukta.mcp.dto.AssignmentInput("server", true, null)),
                true))
        .expectNextCount(1)
        .verifyComplete();
    verify(assignments)
        .set(
            org.mockito.ArgumentMatchers.eq("p"),
            sent.capture(),
            org.mockito.ArgumentMatchers.eq(true));
    assertThat(sent.getValue().get(0).enabled()).isTrue();
    assertThat(sent.getValue().get(0).locked()).isFalse();
  }

  @Test
  void rulesAreReadSavedAndReplayedWithSecretsHidden() {
    final Supervisor rules =
        new Supervisor(
            "r", "rules", java.util.Map.of("rules", java.util.List.of("a"), "token", "abc"), false);
    when(service.supervisors()).thenReturn(Flux.just(rules));
    when(service.versions("r")).thenReturn(Mono.just(java.util.List.of()));
    when(service.secretSettings("rules")).thenReturn(java.util.Set.of("token"));
    when(service.saveSupervisor(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(service.replay(
            org.mockito.ArgumentMatchers.eq("r"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("run1")))
        .thenReturn(Mono.just(java.util.List.of()));

    reactor.test.StepVerifier.create(provider.rules("r"))
        .assertNext(
            doc -> {
              assertThat(doc.version()).isZero();
              assertThat(doc.rules()).containsEntry("token", "***");
            })
        .verifyComplete();
    reactor.test.StepVerifier.create(provider.saveRules("r", null))
        .assertNext(doc -> assertThat(doc.supervisorId()).isEqualTo("r"))
        .verifyComplete();
    reactor.test.StepVerifier.create(
            provider.saveRules("r", java.util.Map.of("rules", java.util.List.of("b"))))
        .assertNext(doc -> assertThat(doc.rules()).containsKey("rules"))
        .verifyComplete();
    reactor.test.StepVerifier.create(
            provider.replayRules("r", "run1", java.util.Map.of("rules", java.util.List.of("c"))))
        .assertNext(replay -> assertThat(replay.decisions()).isEmpty())
        .verifyComplete();
  }

  @Test
  void summaryNamesTheUnownedWorkflows() {
    when(summary.summarize())
        .thenReturn(
            Mono.just(
                new com.infenia.yukta.service.supervisor.SupervisionSummary.Summary(
                    1, List.of(new OwnershipMap.WorkflowRef("s", "w")))));

    StepVerifier.create(provider.summary())
        .assertNext(
            view -> {
              assertThat(view.unowned()).isEqualTo(1);
              assertThat(view.unownedWorkflows())
                  .containsExactly(
                      new com.infenia.yukta.mcp.dto.SupervisionSummaryView.UnownedWorkflow(
                          "s", "w"));
            })
        .verifyComplete();
  }
}
