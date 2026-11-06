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
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.ArrayList;
import java.util.List;
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

  private final SupervisorProvider provider = new SupervisorProvider(host, service);

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
                    0,
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
                    0,
                    0,
                    0,
                    null)));
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
              assertThat(listing.attachments())
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
  void attachParsesTheScopeGeneratesAnIdAndDefaultsToEnabled() {
    when(service.attach(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.attach("p", "workflow:A/w", null, " "))
        .assertNext(
            b -> {
              assertThat(b.id()).isNotBlank();
              assertThat(b.scope()).isEqualTo("workflow:A/w");
              assertThat(b.enabled()).isTrue();
            })
        .verifyComplete();
    StepVerifier.create(provider.attach("p", "workflow:A/w//build.*, deploy", null, null))
        .assertNext(b -> assertThat(b.scope()).isEqualTo("workflow:A/w//build.*,deploy"))
        .verifyComplete();
    StepVerifier.create(provider.attach("p", "global", false, " mine "))
        .assertNext(
            b -> {
              assertThat(b.id()).isEqualTo("mine");
              assertThat(b.enabled()).isFalse();
            })
        .verifyComplete();

    final ArgumentCaptor<SupervisorAttachment> sent =
        ArgumentCaptor.forClass(SupervisorAttachment.class);
    verify(service, org.mockito.Mockito.times(3)).attach(sent.capture());
    assertThat(sent.getAllValues().getFirst().scope())
        .isEqualTo(new SupervisorScope.Workflow("w", "A"));
  }

  @Test
  void attachWithoutBindingIdGeneratesOne() {
    when(service.attach(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.attach("p", "global", true, null))
        .assertNext(b -> assertThat(b.id()).isNotBlank())
        .verifyComplete();
  }

  @Test
  void badScopeIsRejected() {
    StepVerifier.create(provider.attach("p", "everything", true, "b"))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void detachReportsTheRemovedBinding() {
    when(service.detach("b")).thenReturn(Mono.empty());

    StepVerifier.create(provider.detach("b"))
        .assertNext(
            d -> {
              assertThat(d.attachmentId()).isEqualTo("b");
              assertThat(d.detached()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void definitionAttachmentCannotBeDetachedOrReplacedByHand() {
    when(service.attachments())
        .thenReturn(
            Flux.just(
                new SupervisorAttachment(
                    "def-1",
                    "p",
                    new SupervisorScope.Session("A"),
                    true,
                    com.infenia.yukta.plugin.control.SupervisorSource.DEFINITION,
                    "A",
                    null)));

    StepVerifier.create(provider.detach("def-1"))
        .expectError(com.infenia.yukta.service.ConflictException.class)
        .verify();
    StepVerifier.create(provider.attach("p", "global", true, "def-1"))
        .expectError(com.infenia.yukta.service.ConflictException.class)
        .verify();
    verify(service, org.mockito.Mockito.never()).detach(any());
    verify(service, org.mockito.Mockito.never()).attach(any());
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
            com.infenia.yukta.plugin.control.SupervisorCategory.GUARD,
            java.util.Optional.empty(),
            java.util.Map.of("k", 1));
    when(service.types()).thenReturn(List.of("t"));
    when(service.describe("t")).thenReturn(java.util.Optional.of(descriptor));
    when(service.describe("nope")).thenReturn(java.util.Optional.empty());
    when(service.kind("t")).thenReturn("SUPERVISOR");
    when(service.configSchema("t")).thenReturn(List.of());
    when(service.defaultAllowedCommands("t")).thenReturn(java.util.Set.of("B", "A"));

    assertThat(provider.types().types())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.displayName()).isEqualTo("T");
              assertThat(e.kind()).isEqualTo("SUPERVISOR");
            });
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
    when(service.kind("t")).thenReturn("SUPERVISOR");
    when(service.configSchema("t")).thenReturn(List.of());
    when(service.defaultAllowedCommands("t")).thenReturn(null);

    assertThat(provider.type("t").defaultAllowedCommands()).isNull();
  }

  @Test
  void manualAttachmentCanBeDetached() {
    when(service.attachments())
        .thenReturn(
            Flux.just(new SupervisorAttachment("m", "p", new SupervisorScope.Global(), true)));
    when(service.detach("m")).thenReturn(Mono.empty());

    StepVerifier.create(provider.detach("m"))
        .assertNext(d -> assertThat(d.detached()).isTrue())
        .verifyComplete();
  }
}
