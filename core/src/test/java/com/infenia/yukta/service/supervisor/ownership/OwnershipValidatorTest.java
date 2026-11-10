// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.supervisor.ownership.OwnershipValidator.Claim;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Every overlap shape is a conflict, and the shapes that look alike but do not overlap are not. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class OwnershipValidatorTest {

  private static final String RELEASE = "release";

  private final Map<String, WorkflowShape> known =
      new ConcurrentHashMap<>(Map.of(RELEASE, releaseShape()));

  private final OwnershipValidator validator =
      new OwnershipValidator((session, workflow) -> Optional.ofNullable(known.get(workflow)));

  /** The release workflow: build.compile, build.test (group ci), deploy.prod and a lone node. */
  private static WorkflowShape releaseShape() {
    return new WorkflowShape(
        Stream.of(
                entry("build.compile", "build"),
                entry("build.test", "ci"),
                entry("deploy.prod", "deploy"),
                entry("lone", null))
            .<Map<String, String>>collect(
                LinkedHashMap::new,
                (groups, node) -> groups.put(node.getKey(), node.getValue()),
                Map::putAll));
  }

  private static Map.Entry<String, String> entry(final String node, final String group) {
    return new AbstractMap.SimpleEntry<>(node, group);
  }

  private static Claim claim(final String supervisor, final SupervisorScope scope) {
    return new Claim(supervisor, scope);
  }

  private static SupervisorScope.Section nodes(final String... selectors) {
    return new SupervisorScope.Section(RELEASE, "s", Set.of(selectors));
  }

  private List<OwnershipConflict> check(final SupervisorScope mine, final SupervisorScope theirs) {
    return validator.conflicts(claim("a", mine), List.of(claim("b", theirs)));
  }

  @Test
  void workflowAndNodesOverlap() {
    assertThat(check(new SupervisorScope.Workflow(RELEASE, "s"), nodes("lone")))
        .singleElement()
        .satisfies(c -> assertThat(c.message()).contains("workflow release", "owned by b"));
    assertThat(check(nodes("lone"), new SupervisorScope.Workflow(RELEASE, "s"))).hasSize(1);
  }

  @Test
  void sessionAndWorkflowOverlap() {
    assertThat(check(new SupervisorScope.Session("s"), new SupervisorScope.Workflow(RELEASE, "s")))
        .hasSize(1);
    assertThat(check(new SupervisorScope.Session("s"), nodes("lone"))).hasSize(1);
    assertThat(check(new SupervisorScope.Session("s"), new SupervisorScope.Session("t"))).isEmpty();
    assertThat(check(new SupervisorScope.Session("t"), new SupervisorScope.Workflow(RELEASE, "s")))
        .isEmpty();
  }

  @Test
  void prefixAndIdOverlapWhenTheNodeExists() {
    assertThat(check(nodes("build.*"), nodes("build.test")))
        .singleElement()
        .satisfies(c -> assertThat(c.disputed()).contains("node build.test"));
    assertThat(check(nodes("build.*"), nodes("deploy.prod"))).isEmpty();
  }

  @Test
  void prefixAndPrefixOverlapWhenOneBeginsWithTheOther() {
    assertThat(check(nodes("build.*"), nodes("build.test.*"))).hasSize(1);
    assertThat(check(nodes("build.*"), nodes("deploy.*"))).isEmpty();
  }

  @Test
  void selectorsForNodesThatDoNotExistYetAreComparedAsText() {
    known.clear();
    assertThat(check(nodes("build.*"), nodes("build.x"))).hasSize(1);
    assertThat(check(nodes("a.*"), nodes("b.*"))).isEmpty();
    assertThat(check(nodes("same"), nodes("same"))).hasSize(1);
    assertThat(check(nodes("group:ci"), nodes("group:ci"))).hasSize(1);
    assertThat(check(nodes("group:ci"), nodes("build.test"))).isEmpty();
  }

  @Test
  void groupAndAnotherSupervisorsPrefixOverlapOnTheNodesTheyShare() {
    // build.test is in group ci and under build.*
    assertThat(check(nodes("group:ci"), nodes("build.*")))
        .singleElement()
        .satisfies(c -> assertThat(c.disputed()).contains("node build.test"));
    assertThat(check(nodes("group:ci"), nodes("deploy.*"))).isEmpty();
    assertThat(check(nodes("group:ci"), nodes("group:deploy"))).isEmpty();
  }

  @Test
  void runLevelAndNodesCanBeOwnedBySeparateSupervisors() {
    assertThat(check(new SupervisorScope.Run(RELEASE, "s"), nodes("build.*", "deploy.*")))
        .isEmpty();
    assertThat(check(new SupervisorScope.Run(RELEASE, "s"), new SupervisorScope.Run(RELEASE, "s")))
        .hasSize(1);
    assertThat(
            check(
                new SupervisorScope.Run(RELEASE, "s"), new SupervisorScope.Workflow(RELEASE, "s")))
        .hasSize(1);
  }

  @Test
  void theServerHasOneOwner() {
    assertThat(check(new SupervisorScope.Server(), new SupervisorScope.Server())).hasSize(1);
    assertThat(check(new SupervisorScope.Server(), new SupervisorScope.Workflow(RELEASE, "s")))
        .isEmpty();
  }

  @Test
  void supervisorNeverConflictsWithItself() {
    assertThat(
            validator.conflicts(
                claim("a", nodes("lone")),
                List.of(claim("a", new SupervisorScope.Workflow(RELEASE, "s")))))
        .isEmpty();
  }

  @Test
  void newWorkflowVersionCanMakeTwoSelectorsMatchOneNode() {
    final Claim first = claim("a", nodes("build.*"));
    final Claim second = claim("b", nodes("qa.*"));
    assertThat(validator.conflictsAmong(List.of(first, second))).isEmpty();

    // the new version renames deploy.prod to build.prod... and adds qa.x in group build
    known.put(RELEASE, new WorkflowShape(Map.of("build.compile", "build", "qa.x", "build")));
    final Claim byGroup = claim("b", nodes("group:build"));
    assertThat(validator.conflictsAmong(List.of(first, byGroup)))
        .singleElement()
        .satisfies(c -> assertThat(c.disputed()).contains("node build.compile"));
  }

  @Test
  void globalOwnsNothingAndShapesReadTheDeclaredGroup() {
    assertThat(check(new SupervisorScope.Global(), nodes("lone"))).isEmpty();
    assertThat(releaseShape().take("group:ci")).containsExactly("build.test");
    assertThat(releaseShape().take("group:build")).containsExactly("build.compile");
    assertThat(releaseShape().take("lone")).containsExactly("lone");
  }

  @Test
  void theOtherSideCanBeTheGlobalOrTheServer() {
    // Global owns nothing: it only observes
    assertThat(check(nodes("lone"), new SupervisorScope.Global())).isEmpty();
    assertThat(check(new SupervisorScope.Workflow(RELEASE, "s"), new SupervisorScope.Server()))
        .isEmpty();
    assertThat(check(nodes("lone"), new SupervisorScope.Run(RELEASE, "s"))).isEmpty();
  }

  @Test
  void workflowsOfOtherIdsOrSessionsDoNotOverlap() {
    assertThat(
            check(
                new SupervisorScope.Workflow(RELEASE, "s"),
                new SupervisorScope.Workflow("other", "s")))
        .isEmpty();
    assertThat(
            check(
                new SupervisorScope.Workflow(RELEASE, "s"),
                new SupervisorScope.Workflow(RELEASE, "t")))
        .isEmpty();
  }

  @Test
  void sessionOverlapNamesTheSessionEvenWhenTheWorkflowHasNone() {
    assertThat(check(new SupervisorScope.Workflow(RELEASE, null), new SupervisorScope.Session("s")))
        .singleElement()
        .satisfies(c -> assertThat(c.disputed()).isEqualTo("the workflows of session s are"));
  }

  @Test
  void theScopeTextFormsRoundTrip() {
    assertThat(SupervisorScope.parse("server")).isEqualTo(new SupervisorScope.Server());
    assertThat(SupervisorScope.parse("run:s/release"))
        .isEqualTo(new SupervisorScope.Run(RELEASE, "s"));
    assertThat(SupervisorScope.parse("run:release").key()).isEqualTo("run:release");
    assertThat(new SupervisorScope.Run(RELEASE, "s").key()).isEqualTo("run:s/release");
    assertThat(new SupervisorScope.Server().key()).isEqualTo("server");
    assertThat(new SupervisorScope.Run(RELEASE, null).matches(null, RELEASE, "n")).isFalse();
    assertThat(new SupervisorScope.Run(RELEASE, null).matches("s", RELEASE, null)).isTrue();
    assertThat(new SupervisorScope.Server().matches(null, null)).isTrue();
    assertThat(new SupervisorScope.Server().matches("s", RELEASE)).isFalse();
    assertThat(
            new SupervisorScope.Workflow(RELEASE, null)
                .covers(new SupervisorScope.Run(RELEASE, "s")))
        .isTrue();
    assertThat(
            new SupervisorScope.Run(RELEASE, null)
                .covers(new SupervisorScope.Workflow(RELEASE, "s")))
        .isFalse();
  }
}
