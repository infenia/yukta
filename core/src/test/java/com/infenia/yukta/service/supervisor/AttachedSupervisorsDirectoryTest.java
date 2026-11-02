// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.input.EligibleSupervisors;
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
}
