// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.Supervisor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A parent exists, is not the supervisor, makes no cycle and the chain is at most four long. */
@NoArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
class SupervisorHierarchyTest {

  private final Map<String, Supervisor> stored = new HashMap<>();

  private Supervisor sup(final String id, final String... parents) {
    final Supervisor s = new Supervisor(id, "t", Map.of(), false, null, List.of(parents), Map.of());
    stored.put(id, s);
    return s;
  }

  @Test
  void parentsThatExistAndSeveralParentsAreFine() {
    sup("a");
    sup("b");
    assertThatCode(() -> SupervisorHierarchy.validate(sup("c", "a", "b"), stored))
        .doesNotThrowAnyException();
  }

  @Test
  void missingParentSelfReferenceAndCycleAreRefused() {
    sup("a");
    assertThatThrownBy(() -> SupervisorHierarchy.validate(sup("b", "ghost"), stored))
        .hasMessageContaining("ghost");
    assertThatThrownBy(() -> SupervisorHierarchy.validate(sup("c", "c"), stored))
        .hasMessageContaining("itself");
    final Supervisor x = sup("x", "y");
    sup("y");
    final Supervisor y = sup("y", "x");
    assertThatThrownBy(() -> SupervisorHierarchy.validate(y, stored)).hasMessageContaining("cycle");
    assertThat(x).isNotNull();
  }

  @Test
  void chainOfFourIsFineAndFiveIsRefused() {
    sup("l1");
    sup("l2", "l1");
    sup("l3", "l2");
    assertThatCode(() -> SupervisorHierarchy.validate(sup("l4", "l3"), stored))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> SupervisorHierarchy.validate(sup("l5", "l4"), stored))
        .hasMessageContaining("at most 4");
  }

  @Test
  void childrenOfListsWhoReportsToSupervisor() {
    sup("p");
    sup("b", "p");
    sup("a", "p");
    sup("z");
    assertThat(SupervisorHierarchy.childrenOf("p", stored.values())).containsExactly("a", "b");
    assertThat(SupervisorHierarchy.childrenOf("z", stored.values())).isEmpty();
  }
}
