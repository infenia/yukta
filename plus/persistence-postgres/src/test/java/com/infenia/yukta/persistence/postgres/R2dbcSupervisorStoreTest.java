// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import io.r2dbc.spi.ConnectionFactories;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
class R2dbcSupervisorStoreTest extends PostgresTestSupport {

  private final R2dbcSupervisorStore store = new R2dbcSupervisorStore(db, new ObjectMapper());

  private static Supervisor profile(final String id, final boolean dry) {
    return new Supervisor(
        id, "rule-supervisor", Map.of("rules", List.of(Map.of("id", "r")), "level", 2), dry);
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
  void renamedIdsAreReadWithoutFailing() {
    db.sql(
            "INSERT INTO supervisor_id_migration (kind, old_id, new_id) VALUES ('profile', 'a:b',"
                + " 'a_b')")
        .fetch()
        .rowsUpdated()
        .block();
    try {
      StepVerifier.create(store.renamedIdsWarning()).verifyComplete();
      store.warnRenamedIds();
    } finally {
      db.sql("DELETE FROM supervisor_id_migration").fetch().rowsUpdated().block();
    }
  }

  @Test
  void anUnreadableMappingTableIsLoggedNotRaised() {
    final R2dbcSupervisorStore broken =
        new R2dbcSupervisorStore(
            DatabaseClient.create(
                ConnectionFactories.get("r2dbc:postgresql://u:p@127.0.0.1:1/none")),
            new ObjectMapper());

    StepVerifier.create(broken.renamedIdsWarning()).verifyComplete();
  }
}
