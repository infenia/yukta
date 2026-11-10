// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import com.infenia.yukta.service.input.EligibleSupervisors;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.review.AttachedSupervisors;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.runtime.ExecutionControl;
import com.infenia.yukta.service.runtime.store.ExecutionControlStore;
import com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The directory lists the attached supervisors with their scope. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AttachedSupervisorsDirectoryTest {

  private static final InputRequest REQUEST =
      InputTestSupport.request("r", InputTestSupport.spec("k"));

  @Test
  void listsEveryAttachedSupervisorWithItsScope() {
    final BoundSupervisor bound = mock(BoundSupervisor.class);
    final SupervisorScope scope = SupervisorScope.parse("workflow:session/workflow");
    when(bound.id()).thenReturn("watch");
    when(bound.scope()).thenReturn(scope);
    final AttachedSupervisors attached = mock(AttachedSupervisors.class);
    when(attached.stream()).thenReturn(Stream.of(bound));

    assertThat(new AttachedSupervisorsDirectory(attached).bound())
        .containsExactly(new EligibleSupervisors.Candidate("watch", scope));
    assertThat(EligibleSupervisors.NONE.bound()).isEmpty();
  }

  private static BoundSupervisor attached(final String id, final String scope) {
    final BoundSupervisor bound = mock(BoundSupervisor.class);
    when(bound.id()).thenReturn(id);
    when(bound.scope()).thenReturn(SupervisorScope.parse(scope));
    return bound;
  }

  private static EligibleSupervisors.Candidate candidate(final String id, final String scope) {
    return new EligibleSupervisors.Candidate(id, SupervisorScope.parse(scope));
  }

  private static ExecutionControlStore runWith(final Map<String, Object> payload) {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.payload()).thenReturn(payload);
    final ExecutionControlStore store = mock(ExecutionControlStore.class);
    when(store.findByExecutionId("exec")).thenReturn(Optional.of(control));
    return store;
  }

  @Test
  void requestFromSupervisorRunGoesOnlyToItsParents() {
    final AttachedSupervisors attached = mock(AttachedSupervisors.class);
    final BoundSupervisor secBound = attached("sec", "workflow:s/w//a.*");
    final BoundSupervisor wfBound = attached("wf", "workflow:s/w");
    final SupervisorAttachment secAttachment =
        new SupervisorAttachment("sec", "sec-profile", secBound.scope(), true);
    final SupervisorAttachment wfAttachment =
        new SupervisorAttachment("wf", "wf-profile", wfBound.scope(), true);
    when(secBound.attachment()).thenReturn(secAttachment);
    when(wfBound.attachment()).thenReturn(wfAttachment);
    when(attached.stream()).thenAnswer(_ -> Stream.of(secBound, wfBound));
    final var directory =
        new AttachedSupervisorsDirectory(
            attached,
            runWith(
                Map.of(
                    DefaultAnswerAuthorizer.DEPTH_KEY,
                    1,
                    DefaultAnswerAuthorizer.STARTER_KEY,
                    "sec")));

    final Supervisor profile =
        new Supervisor("sec-profile", "t", Map.of(), false, null, List.of("wf-profile"), Map.of());
    final SupervisorResolver resolver = mock(SupervisorResolver.class);
    when(resolver.supervisor("sec-profile")).thenReturn(Optional.of(profile));
    when(resolver.supervisor("wf-profile")).thenReturn(Optional.empty());
    final ObjectProvider<SupervisorResolver> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(resolver);
    directory.setResolver(provider);

    assertThat(directory.parentsOf("sec")).containsExactly("wf");
    assertThat(directory.parentsOf("wf")).isEmpty();
    assertThat(directory.parentsOf("nobody")).isEmpty();
    assertThat(directory.mayReceive(candidate("wf", "workflow:s/w"), REQUEST)).isTrue();
    assertThat(directory.mayReceive(candidate("sec", "workflow:s/w//a.*"), REQUEST)).isFalse();
    assertThat(directory.mayReceive(candidate("narrow", "workflow:s/w//a.b"), REQUEST)).isFalse();
  }

  @Test
  void goneSupervisorLeavesOnlyPeople() {
    final AttachedSupervisors attached = mock(AttachedSupervisors.class);
    when(attached.stream()).thenAnswer(_ -> Stream.<BoundSupervisor>empty());
    final var directory =
        new AttachedSupervisorsDirectory(
            attached,
            runWith(
                Map.of(
                    DefaultAnswerAuthorizer.DEPTH_KEY,
                    1,
                    DefaultAnswerAuthorizer.STARTER_KEY,
                    "gone")));

    assertThat(directory.mayReceive(candidate("wf", "global"), REQUEST)).isFalse();
  }

  @Test
  void anOrdinaryRunOrNoRunStoreLeavesEveryoneEligible() {
    final AttachedSupervisors attached = mock(AttachedSupervisors.class);
    final var candidate = candidate("wf", "global");

    assertThat(new AttachedSupervisorsDirectory(attached).mayReceive(candidate, REQUEST)).isTrue();
    assertThat(
            new AttachedSupervisorsDirectory(attached, runWith(Map.of()))
                .mayReceive(candidate, REQUEST))
        .isTrue();
    assertThat(
            new AttachedSupervisorsDirectory(
                    attached, runWith(Map.of(DefaultAnswerAuthorizer.DEPTH_KEY, 0)))
                .mayReceive(candidate, REQUEST))
        .isTrue();
    final ExecutionControlStore none = mock(ExecutionControlStore.class);
    when(none.findByExecutionId("exec")).thenReturn(Optional.empty());
    assertThat(new AttachedSupervisorsDirectory(attached, none).mayReceive(candidate, REQUEST))
        .isTrue();
    assertThat(EligibleSupervisors.NONE.mayReceive(candidate, REQUEST)).isTrue();
  }

  @Test
  void withoutResolverNobodyHasParents() {
    final AttachedSupervisors attached = mock(AttachedSupervisors.class);
    final AttachedSupervisorsDirectory directory = new AttachedSupervisorsDirectory(attached);
    assertThat(directory.parentsOf("sec")).isEmpty();

    final ObjectProvider<SupervisorResolver> provider = mock(ObjectProvider.class);
    directory.setResolver(provider);
    assertThat(directory.parentsOf("sec")).isEmpty();
  }

  @Test
  void outranksFollowsParentsUpToFourLevels() {
    final EligibleSupervisors endless =
        new EligibleSupervisors() {
          @Override
          public List<Candidate> bound() {
            return List.of();
          }

          @Override
          public List<String> parentsOf(final String candidateId) {
            return List.of(candidateId + "^");
          }
        };

    assertThat(endless.outranks("a^^^^", "a")).isTrue();
    assertThat(endless.outranks("a^^^^^", "a")).isFalse();
  }
}
