// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.SupervisorScope;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * What two sections share when their selectors take real nodes, and when they only read as text.
 */
@NoArgsConstructor
class SectionOverlapTest {

  private static final String RELEASE = "release";
  private static final String BUILD_ALL = "build.*";
  private static final String BUILD_TEST = "build.test";
  private static final String BUILD_X = "build.x";
  private static final String BUILD_TEST_ALL = "build.test.*";
  private static final String DEPLOY_ALL = "deploy.*";
  private static final String GROUP_CI = "group:ci";

  private static final WorkflowShape SHAPE =
      new WorkflowShape(
          Map.of("build.compile", "build", BUILD_TEST, "ci", "deploy.prod", "deploy"));

  private static SupervisorScope.Section section(final String... selectors) {
    return new SupervisorScope.Section(RELEASE, "s", Set.of(selectors));
  }

  /** The overlap when the shape of the workflow is known. */
  private static Optional<String> known(
      final SupervisorScope.Section a, final SupervisorScope.Section b) {
    return SectionOverlap.between(Optional.of(SHAPE), RELEASE, a, b);
  }

  /** The overlap when the shape of the workflow is not known: selectors are compared as text. */
  private static Optional<String> unknown(
      final SupervisorScope.Section a, final SupervisorScope.Section b) {
    return SectionOverlap.between(Optional.empty(), RELEASE, a, b);
  }

  @Test
  void nodesTakenByBothSectionsAreNamed() {
    assertThat(known(section(BUILD_ALL), section(BUILD_TEST)))
        .hasValue("node build.test of release are");
    assertThat(known(section(BUILD_TEST), section(BUILD_ALL)))
        .hasValue("node build.test of release are");
  }

  @Test
  void sectionsOfResolvedNodesThatDiffer_shareNothing() {
    assertThat(known(section(DEPLOY_ALL), section(BUILD_ALL))).isEmpty();
    assertThat(known(section(BUILD_ALL), section("qa.*"))).isEmpty();
  }

  @Test
  void selectorsThatDoNotExistYetAreComparedAsText() {
    assertThat(unknown(section(BUILD_ALL), section(BUILD_X)))
        .hasValue("selector build.* and build.x of release are");
    assertThat(unknown(section("same"), section("same"))).hasValue("selector same of release are");
    assertThat(unknown(section("x"), section("y"))).isEmpty();
  }

  @Test
  void prefixAndIdOverlapInEitherOrder() {
    assertThat(unknown(section(BUILD_X), section(BUILD_ALL)))
        .hasValue("selector build.x and build.* of release are");
    assertThat(unknown(section(BUILD_ALL), section(BUILD_X)))
        .hasValue("selector build.* and build.x of release are");
    assertThat(unknown(section("deploy.x"), section(BUILD_ALL))).isEmpty();
  }

  @Test
  void twoPrefixesOverlapWhenOneBeginsWithTheOther() {
    assertThat(unknown(section(BUILD_TEST_ALL), section(BUILD_ALL)))
        .hasValue("selector build.test.* and build.* of release are");
    assertThat(unknown(section(BUILD_ALL), section(BUILD_TEST_ALL)))
        .hasValue("selector build.* and build.test.* of release are");
    assertThat(unknown(section("a.*"), section("b.*"))).isEmpty();
  }

  @Test
  void groupOverlapsOnlyTheSameGroup() {
    assertThat(unknown(section(GROUP_CI), section(GROUP_CI)))
        .hasValue("selector group:ci of release are");
    assertThat(unknown(section(BUILD_ALL), section(GROUP_CI))).isEmpty();
    assertThat(unknown(section(GROUP_CI), section(BUILD_ALL))).isEmpty();
  }
}
