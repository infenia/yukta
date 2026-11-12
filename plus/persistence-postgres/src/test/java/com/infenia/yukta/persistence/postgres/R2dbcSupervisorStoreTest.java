// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.plugin.control.Delegation;
import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.DelegationMode;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.supervisor.SupervisorVersions;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods"})
class R2dbcSupervisorStoreTest extends PostgresTestSupport {

  private static final String RULES = "rules";
  private static final String LEVEL = "level";

  private final R2dbcSupervisorStore store = new R2dbcSupervisorStore(db, new ObjectMapper());

  private static Supervisor profile(final String id, final boolean dry) {
    return new Supervisor(
        id, "rule-supervisor", Map.of(RULES, List.of(Map.of("id", "r")), LEVEL, 2), dry);
  }

  @Test
  void profilesAreStoredReplacedAndDeleted() {
    StepVerifier.create(store.supervisors()).verifyComplete();

    StepVerifier.create(store.saveSupervisor(profile("b", false))).verifyComplete();
    StepVerifier.create(store.saveSupervisor(profile("a", true))).verifyComplete();
    StepVerifier.create(store.supervisors())
        .expectNext(profile("a", true), profile("b", false))
        .verifyComplete();

    StepVerifier.create(store.saveSupervisor(profile("a", false))).verifyComplete();
    StepVerifier.create(store.supervisors().filter(p -> "a".equals(p.id())))
        .expectNext(profile("a", false))
        .verifyComplete();

    StepVerifier.create(store.deleteSupervisor("a")).expectNext(true).verifyComplete();
    StepVerifier.create(store.deleteSupervisor("a")).expectNext(false).verifyComplete();
  }

  @Test
  void bindingsKeepTheirScopeAndAreStoredReplacedAndDeleted() {
    StepVerifier.create(store.saveSupervisor(profile("p", false))).verifyComplete();
    final SupervisorAttachment session =
        new SupervisorAttachment("b1", "p", new SupervisorScope.Session("A"), true);
    final SupervisorAttachment workflow =
        new SupervisorAttachment("b2", "p", new SupervisorScope.Workflow("w", "A"), false);

    StepVerifier.create(store.saveAttachment(workflow)).verifyComplete();
    StepVerifier.create(store.saveAttachment(session)).verifyComplete();
    StepVerifier.create(store.attachments()).expectNext(session, workflow).verifyComplete();

    final SupervisorAttachment global =
        new SupervisorAttachment("b1", "p", new SupervisorScope.Global(), false);
    StepVerifier.create(store.saveAttachment(global)).verifyComplete();
    StepVerifier.create(store.attachments().filter(b -> "b1".equals(b.id())))
        .expectNext(global)
        .verifyComplete();

    StepVerifier.create(store.deleteAttachment("b1")).expectNext(true).verifyComplete();
    StepVerifier.create(store.deleteAttachment("b1")).expectNext(false).verifyComplete();
  }

  @Test
  void descriptionSourceAndOriginAreStoredAndReadBack() {
    final Supervisor described =
        new Supervisor("d", "rule-supervisor", Map.of(), false, "pauses after failures");
    final SupervisorAttachment declared =
        new SupervisorAttachment(
            "def-1",
            "d",
            new SupervisorScope.Session("A"),
            true,
            SupervisorSource.DEFINITION,
            "A",
            "w1");

    StepVerifier.create(store.saveSupervisor(described)).verifyComplete();
    StepVerifier.create(store.saveAttachment(declared)).verifyComplete();

    StepVerifier.create(store.supervisors()).expectNext(described).verifyComplete();
    StepVerifier.create(store.attachments()).expectNext(declared).verifyComplete();
  }

  @Test
  void parentsAndDelegationModesAreStoredAndReadBack() {
    final Supervisor child =
        new Supervisor(
            "child",
            "rule-supervisor",
            Map.of(),
            false,
            null,
            List.of("p1", "p2"),
            Map.of(
                DelegationKind.APPROVAL,
                new Delegation(DelegationMode.ALL, java.time.Duration.ofSeconds(45))));

    StepVerifier.create(store.saveSupervisor(child)).verifyComplete();

    StepVerifier.create(store.supervisors()).expectNext(child).verifyComplete();
  }

  @Test
  void lockIsStoredAndReadBack() {
    final SupervisorAttachment locked =
        new SupervisorAttachment("l1", "p", new SupervisorScope.Run("w", "A"), true)
            .withLocked(true);

    StepVerifier.create(store.saveSupervisor(profile("p", false))).verifyComplete();
    StepVerifier.create(store.saveAttachment(locked)).verifyComplete();

    StepVerifier.create(store.attachments().filter(a -> "l1".equals(a.id())))
        .assertNext(a -> assertThat(a.locked()).isTrue())
        .verifyComplete();
  }

  @Test
  void conflictMarkIsStoredAndReadBack() {
    final SupervisorAttachment marked =
        new SupervisorAttachment("c1", "p", new SupervisorScope.Run("w", "A"), true).asConflict();

    StepVerifier.create(store.saveSupervisor(profile("p", false))).verifyComplete();
    StepVerifier.create(store.saveAttachment(marked)).verifyComplete();

    StepVerifier.create(store.attachments())
        .assertNext(
            a -> {
              assertThat(a.conflict()).isTrue();
              assertThat(a.enabled()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void sectionScopeIsStoredAsTextAndReadBack() {
    StepVerifier.create(store.saveSupervisor(profile("p", false))).verifyComplete();
    final Set<String> nodes = new LinkedHashSet<>();
    for (int i = 0; i < SupervisorScope.Section.MAX_SELECTORS; i++) {
      nodes.add("stage" + i + ".*");
    }
    final SupervisorAttachment section =
        new SupervisorAttachment("b3", "p", new SupervisorScope.Section("w#1", "A", nodes), true);

    StepVerifier.create(store.saveAttachment(section)).verifyComplete();
    StepVerifier.create(store.attachments()).expectNext(section).verifyComplete();
  }

  @Test
  void bindingNeedsItsProfile() {
    StepVerifier.create(
            store.saveAttachment(
                new SupervisorAttachment("b", "ghost", new SupervisorScope.Global(), true)))
        .expectError()
        .verify();
  }

  @Test
  void sessionRefsAreKeptReplacedAndForgotten() {
    final List<SupervisorRef> refs =
        List.of(new SupervisorRef("guard", null, false), new SupervisorRef("watch", null, null));

    StepVerifier.create(store.sessionRefs("A")).expectNext(List.of()).verifyComplete();
    StepVerifier.create(store.saveSessionRefs("A", refs)).verifyComplete();
    StepVerifier.create(store.saveSessionRefs("B", refs)).verifyComplete();
    StepVerifier.create(store.sessionRefs("A")).expectNext(refs).verifyComplete();

    StepVerifier.create(store.saveSessionRefs("A", List.of(refs.getFirst()))).verifyComplete();
    StepVerifier.create(store.sessionRefs("A"))
        .expectNext(List.of(refs.getFirst()))
        .verifyComplete();
    StepVerifier.create(store.saveSessionRefs("A", List.of())).verifyComplete();
    StepVerifier.create(store.sessionRefs("A")).expectNext(List.of()).verifyComplete();
  }

  @Test
  void versionsCountUpSkipAnUnchangedSaveAndKeepOnlyTheNewest() {
    final Supervisor full =
        new Supervisor(
            "v",
            RULES,
            Map.of(RULES, List.of(Map.of("id", "r")), LEVEL, 2),
            true,
            "pauses after failures",
            List.of("parent"),
            Map.of(
                DelegationKind.APPROVAL,
                new Delegation(DelegationMode.ANY, java.time.Duration.ofSeconds(30))));
    StepVerifier.create(store.saveVersion(full, Instant.EPOCH, 3)).verifyComplete();
    // the same settings with a long instead of an int are the same JSON: no new version
    StepVerifier.create(
            store.saveVersion(
                full.withEngine(RULES, Map.of(LEVEL, 2L, RULES, List.of(Map.of("id", "r")))),
                Instant.EPOCH,
                3))
        .verifyComplete();
    StepVerifier.create(store.versions("v"))
        .assertNext(
            v -> {
              assertThat(v.version()).isEqualTo(1);
              assertThat(v.savedAt()).isEqualTo(Instant.EPOCH);
              assertThat(v.supervisor()).isEqualTo(full);
            })
        .verifyComplete();

    for (int level = 3; level <= 6; level++) {
      StepVerifier.create(
              store.saveVersion(full.withEngine(RULES, Map.of(LEVEL, level)), Instant.EPOCH, 3))
          .verifyComplete();
    }
    StepVerifier.create(store.versions("v").map(SupervisorVersions.Version::version))
        .expectNext(5, 4, 3)
        .verifyComplete();
    StepVerifier.create(store.versions("other")).verifyComplete();

    StepVerifier.create(store.deleteVersions("v")).verifyComplete();
    StepVerifier.create(store.versions("v")).verifyComplete();
  }
}
