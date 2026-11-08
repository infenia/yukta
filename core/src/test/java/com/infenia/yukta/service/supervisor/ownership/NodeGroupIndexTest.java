// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A group selector matches the nodes of its group in the stored workflow of its session. */
class NodeGroupIndexTest {

  private final NodeGroupIndex index = new NodeGroupIndex();

  private static Map<String, WorkflowDefinition> workflow(final String... nodes) {
    return Map.of(
        "w",
        Fixtures.workflow(
            "w",
            "d",
            java.util.Arrays.stream(nodes).map(n -> Fixtures.node(n, "T", Map.of())).toList(),
            List.of(),
            List.of()));
  }

  @Test
  void groupResolvesToTheNodesOfThatStageAndFollowsNewVersions() {
    final SupervisorScope ci = new SupervisorScope.Section("w", "s", Set.of("group:build"));
    // nothing known yet: the group owns nothing rather than too much
    assertThat(index.resolve(ci).matches("s", "w", "build.a")).isFalse();

    assertThat(index.put("s", workflow("build.a", "build.b", "deploy"))).isTrue();
    final SupervisorScope resolved = index.resolve(ci);
    assertThat(resolved.matches("s", "w", "build.a")).isTrue();
    assertThat(resolved.matches("s", "w", "deploy")).isFalse();
    assertThat(resolved.key()).isEqualTo(ci.key());

    // the same definitions change nothing; a new node joins the group
    assertThat(index.put("s", workflow("build.a", "build.b", "deploy"))).isFalse();
    assertThat(index.put("s", workflow("build.a", "build.c"))).isTrue();
    assertThat(index.resolve(ci).matches("s", "w", "build.c")).isTrue();

    index.forget("s");
    assertThat(index.resolve(ci).matches("s", "w", "build.a")).isFalse();
  }

  @Test
  void scopesWithoutGroupsOrWithoutSessionAreLeftAsTheyAre() {
    index.put("s", workflow("build.a"));
    final SupervisorScope plain = new SupervisorScope.Section("w", "s", Set.of("build.*"));
    final SupervisorScope anySession = new SupervisorScope.Section("w", null, Set.of("group:build"));

    assertThat(index.resolve(plain)).isSameAs(plain);
    assertThat(index.resolve(anySession)).isSameAs(anySession);
    assertThat(index.resolve(new SupervisorScope.Workflow("w", "s"))).isEqualTo(
        new SupervisorScope.Workflow("w", "s"));
  }
}
