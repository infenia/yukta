// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import io.r2dbc.spi.ConnectionFactories;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
class R2dbcSupervisorStoreTest extends PostgresTestSupport {

  private final R2dbcSupervisorStore store = new R2dbcSupervisorStore(db, new ObjectMapper());

  private static SupervisorProfile profile(final String id, final boolean dry) {
    return new SupervisorProfile(
        id, "rule-supervisor", Map.of("rules", List.of(Map.of("id", "r")), "level", 2), dry);
  }

  @Test
  void profilesAreStoredReplacedAndDeleted() {
    StepVerifier.create(store.profiles()).verifyComplete();

    StepVerifier.create(store.saveProfile(profile("b", false))).verifyComplete();
    StepVerifier.create(store.saveProfile(profile("a", true))).verifyComplete();
    StepVerifier.create(store.profiles())
        .expectNext(profile("a", true), profile("b", false))
        .verifyComplete();

    StepVerifier.create(store.saveProfile(profile("a", false))).verifyComplete();
    StepVerifier.create(store.profiles().filter(p -> "a".equals(p.id())))
        .expectNext(profile("a", false))
        .verifyComplete();

    StepVerifier.create(store.deleteProfile("a")).expectNext(true).verifyComplete();
    StepVerifier.create(store.deleteProfile("a")).expectNext(false).verifyComplete();
  }

  @Test
  void bindingsKeepTheirScopeAndAreStoredReplacedAndDeleted() {
    StepVerifier.create(store.saveProfile(profile("p", false))).verifyComplete();
    final SupervisorBinding session =
        new SupervisorBinding("b1", "p", new SupervisorScope.Session("A"), true);
    final SupervisorBinding workflow =
        new SupervisorBinding("b2", "p", new SupervisorScope.Workflow("w", "A"), false);

    StepVerifier.create(store.saveBinding(workflow)).verifyComplete();
    StepVerifier.create(store.saveBinding(session)).verifyComplete();
    StepVerifier.create(store.bindings()).expectNext(session, workflow).verifyComplete();

    final SupervisorBinding global =
        new SupervisorBinding("b1", "p", new SupervisorScope.Global(), false);
    StepVerifier.create(store.saveBinding(global)).verifyComplete();
    StepVerifier.create(store.bindings().filter(b -> "b1".equals(b.id())))
        .expectNext(global)
        .verifyComplete();

    StepVerifier.create(store.deleteBinding("b1")).expectNext(true).verifyComplete();
    StepVerifier.create(store.deleteBinding("b1")).expectNext(false).verifyComplete();
  }

  @Test
  void bindingNeedsItsProfile() {
    StepVerifier.create(
            store.saveBinding(
                new SupervisorBinding("b", "ghost", new SupervisorScope.Global(), true)))
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
