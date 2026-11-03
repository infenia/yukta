// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import com.infenia.yukta.service.input.EligibleSupervisors;
import com.infenia.yukta.service.input.InputTestSupport;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The directory lists the attached supervisors with their scope. */
@NoArgsConstructor
class AttachedSupervisorsDirectoryTest {

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

  private static final InputRequest REQUEST =
      InputTestSupport.request("r", InputTestSupport.spec("k"));

  @Test
  void aRequestFromASupervisorRunGoesOnlyToSupervisorsThatRankAbove() {
    final AttachedSupervisors attached = mock(AttachedSupervisors.class);
    when(attached.stream())
        .thenAnswer(
            _ -> Stream.of(attached("sec", "workflow:s/w//a.*"), attached("wf", "workflow:s/w")));
    final var directory =
        new AttachedSupervisorsDirectory(
            attached,
            runWith(
                Map.of(
                    DefaultAnswerAuthorizer.DEPTH_KEY,
                    1,
                    DefaultAnswerAuthorizer.STARTER_KEY,
                    "sec")));

    assertThat(directory.mayReceive(candidate("wf", "workflow:s/w"), REQUEST)).isTrue();
    assertThat(directory.mayReceive(candidate("sec", "workflow:s/w//a.*"), REQUEST)).isFalse();
    assertThat(directory.mayReceive(candidate("narrow", "workflow:s/w//a.b"), REQUEST)).isFalse();
  }

  @Test
  void aSupervisorThatIsGoneLeavesOnlyPeople() {
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
}
