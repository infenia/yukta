// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link SupervisorScope.Section}, node matching and {@link SupervisorScope#covers}. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
@NoArgsConstructor
class SupervisorSectionScopeTest {

  private static final SupervisorScope GLOBAL = new SupervisorScope.Global();

  private static final SupervisorScope SESSION_A = new SupervisorScope.Session("A");

  private static final SupervisorScope SESSION_B = new SupervisorScope.Session("B");

  private static final SupervisorScope WORKFLOW_ANY = new SupervisorScope.Workflow("w", null);

  private static final SupervisorScope WORKFLOW_A = new SupervisorScope.Workflow("w", "A");

  private static final SupervisorScope WORKFLOW_B = new SupervisorScope.Workflow("w", "B");

  private static final SupervisorScope OTHER_WORKFLOW = new SupervisorScope.Workflow("x", "A");

  private static SupervisorScope.Section section(final String session, final String... nodes) {
    return new SupervisorScope.Section("w", session, new LinkedHashSet<>(List.of(nodes)));
  }

  @Test
  void sectionReadsAndWritesItsTextForm() {
    for (final String key :
        List.of("workflow:A/w//build.*,deploy", "workflow:w//build.*", "workflow:A#1/w#2//n#3")) {
      assertThat(SupervisorScope.parse(key).key()).isEqualTo(key);
    }
    assertThat(SupervisorScope.parse(" workflow:A/w// build.* , deploy "))
        .isEqualTo(section("A", "build.*", "deploy"));
    assertThat(SupervisorScope.parse("workflow:w//a").sessionId()).isNull();
    assertThat(SupervisorScope.parse("workflow:A/w//a").kind())
        .isEqualTo(SupervisorScope.Kind.SECTION);
  }

  @Test
  void slashAfterTheSeparatorBelongsToTheSelector() {
    assertThat(SupervisorScope.parse("workflow:A///a"))
        .isEqualTo(new SupervisorScope.Section("A", null, Set.of("/a")));
  }

  @Test
  void hashInIdsStaysPlainWorkflow() {
    assertThat(SupervisorScope.parse("workflow:A/w#build.*"))
        .isEqualTo(new SupervisorScope.Workflow("w#build.*", "A"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "workflow://a",
        "workflow:A/w//",
        "workflow:A/w//a,,b",
        "workflow:A/w//a, ",
        "workflow:A/w//.*",
        "workflow:/w//a"
      })
  void rejectsBadSection(final String text) {
    assertThatThrownBy(() -> SupervisorScope.parse(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(SupervisorScope.BAD_SCOPE);
  }

  @Test
  void limitsTheSelectors() {
    final Set<String> many = new LinkedHashSet<>();
    IntStream.range(0, SupervisorScope.Section.MAX_SELECTORS + 1).forEach(i -> many.add("n" + i));
    assertThatThrownBy(() -> new SupervisorScope.Section("w", null, many))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SupervisorScope.Section("w", null, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SupervisorScope.Section("w", null, Set.of("a".repeat(257))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SupervisorScope.Section("w", null, Set.of("a,b")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SupervisorScope.Section("w", null, Collections.singleton((String) null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SupervisorScope.Section(null, null, Set.of("a")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorScope.Section("w", null, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(new SupervisorScope.Section("w", null, Set.of("a".repeat(256))).nodes()).hasSize(1);
  }

  @Test
  void keepsAnUnmodifiableCopyOfTheSelectors() {
    final Set<String> nodes = new LinkedHashSet<>(List.of("a"));
    final SupervisorScope.Section scope = new SupervisorScope.Section("w", null, nodes);
    nodes.add("b");

    assertThat(scope.nodes()).containsExactly("a");
    assertThatThrownBy(() -> scope.nodes().add("c"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void sectionMatchesItsNodesByIdOrPrefix() {
    final SupervisorScope scope = section("A", "build.*", "publish");

    assertThat(scope.matches("A", "w", "build.compile")).isTrue();
    assertThat(scope.matches("A", "w", "build.test.unit")).isTrue();
    assertThat(scope.matches("A", "w", "publish")).isTrue();
    assertThat(scope.matches("A", "w", "build")).isFalse();
    assertThat(scope.matches("A", "w", "builder.x")).isFalse();
    assertThat(scope.matches("A", "w", "publish.x")).isFalse();
    assertThat(scope.matches("A", "w", "deploy.prod")).isFalse();
    assertThat(scope.matches("B", "w", "publish")).isFalse();
    assertThat(scope.matches("A", "x", "publish")).isFalse();
    assertThat(section(null, "publish").matches("any", "w", "publish")).isTrue();
  }

  @Test
  void sectionDoesNotMatchItemsWithoutNode() {
    final SupervisorScope scope = section("A", "build.*");

    assertThat(scope.matches("A", "w")).isFalse();
    assertThat(scope.matches("A", "w", null)).isFalse();
  }

  @Test
  void otherScopesIgnoreTheNode() {
    assertThat(GLOBAL.matches(null, null, null)).isTrue();
    assertThat(SESSION_A.matches("A", "w", "n")).isTrue();
    assertThat(WORKFLOW_A.matches("A", "w", "n")).isTrue();
    assertThat(WORKFLOW_A.matches("A", "x", "n")).isFalse();
  }

  @Test
  void coversByKind() {
    final SupervisorScope sectionA = section("A", "build.*");
    final SupervisorScope sectionAny = section(null, "build.*");

    assertThat(GLOBAL.covers(GLOBAL)).isTrue();
    assertThat(GLOBAL.covers(sectionA)).isTrue();
    assertThat(SESSION_A.covers(GLOBAL)).isFalse();
    assertThat(SESSION_A.covers(SESSION_A)).isTrue();
    assertThat(SESSION_A.covers(SESSION_B)).isFalse();
    assertThat(SESSION_A.covers(WORKFLOW_A)).isTrue();
    assertThat(SESSION_A.covers(WORKFLOW_ANY)).isFalse();
    assertThat(SESSION_A.covers(sectionA)).isTrue();
    assertThat(SESSION_A.covers(sectionAny)).isFalse();
    assertThat(WORKFLOW_A.covers(GLOBAL)).isFalse();
    assertThat(WORKFLOW_A.covers(SESSION_A)).isFalse();
    assertThat(WORKFLOW_A.covers(WORKFLOW_A)).isTrue();
    assertThat(WORKFLOW_A.covers(WORKFLOW_B)).isFalse();
    assertThat(WORKFLOW_A.covers(WORKFLOW_ANY)).isFalse();
    assertThat(WORKFLOW_A.covers(OTHER_WORKFLOW)).isFalse();
    assertThat(WORKFLOW_A.covers(sectionA)).isTrue();
    assertThat(WORKFLOW_A.covers(sectionAny)).isFalse();
    assertThat(WORKFLOW_ANY.covers(WORKFLOW_A)).isTrue();
    assertThat(WORKFLOW_ANY.covers(sectionA)).isTrue();
    assertThat(sectionA.covers(GLOBAL)).isFalse();
    assertThat(sectionA.covers(SESSION_A)).isFalse();
    assertThat(sectionA.covers(WORKFLOW_A)).isFalse();
    assertThat(sectionA.covers(sectionA)).isTrue();
    assertThat(sectionA.covers(sectionAny)).isFalse();
    assertThat(sectionAny.covers(sectionA)).isTrue();
    assertThat(sectionA.covers(section("B", "build.*"))).isFalse();
    assertThat(
            sectionA.covers(
                new SupervisorScope.Section("x", "A", new LinkedHashSet<>(List.of("a")))))
        .isFalse();
  }

  @Test
  void sectionCoversTheSelectorsItsOwnTake() {
    final SupervisorScope wide = section("A", "build.*", "publish");

    assertThat(wide.covers(section("A", "build.compile"))).isTrue();
    assertThat(wide.covers(section("A", "build.test.*"))).isTrue();
    assertThat(wide.covers(section("A", "build.*", "publish"))).isTrue();
    assertThat(wide.covers(section("A", "publish"))).isTrue();
    assertThat(wide.covers(section("A", "build"))).isFalse();
    assertThat(wide.covers(section("A", "builder.*"))).isFalse();
    assertThat(wide.covers(section("A", "publish.*"))).isFalse();
    assertThat(wide.covers(section("A", "build.x", "deploy"))).isFalse();
    assertThat(section("A", "build.x").covers(section("A", "build.*"))).isFalse();
  }

  @Test
  void strictCoverIsIrreflexiveAndHasNoCycle() {
    final List<SupervisorScope> scopes =
        List.of(
            GLOBAL,
            SESSION_A,
            SESSION_B,
            WORKFLOW_ANY,
            WORKFLOW_A,
            WORKFLOW_B,
            OTHER_WORKFLOW,
            section("A", "build.*"),
            section("A", "build.*", "build.x"),
            section("A", "build.x"),
            section("A", "build.test.*"),
            section(null, "build.*"),
            section("B", "deploy"));

    for (final SupervisorScope a : scopes) {
      assertThat(a.strictlyCovers(a)).as(a.key()).isFalse();
      for (final SupervisorScope b : scopes) {
        assertThat(a.strictlyCovers(b) && b.strictlyCovers(a))
            .as(a.key() + " " + b.key())
            .isFalse();
        for (final SupervisorScope c : scopes) {
          if (a.strictlyCovers(b) && b.strictlyCovers(c)) {
            assertThat(a.strictlyCovers(c))
                .as(a.key() + " > " + b.key() + " > " + c.key())
                .isTrue();
          }
        }
      }
    }
    // equal in what they take, though written differently: neither is above the other
    assertThat(section("A", "build.*").strictlyCovers(section("A", "build.*", "build.x")))
        .isFalse();
    assertThat(GLOBAL.strictlyCovers(SESSION_A)).isTrue();
    assertThat(SESSION_A.strictlyCovers(WORKFLOW_A)).isTrue();
    assertThat(WORKFLOW_A.strictlyCovers(section("A", "build.*"))).isTrue();
    assertThat(section("A", "build.*").strictlyCovers(section("A", "build.x"))).isTrue();
  }
}
