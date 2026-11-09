// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.supervisor.agent.AgentCredentialStore.Stored;
import java.time.Instant;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidUsingHardCodedIP"})
class R2dbcAgentCredentialStoreTest extends PostgresTestSupport {

  private final R2dbcAgentCredentialStore store = new R2dbcAgentCredentialStore(db);

  /** The tests share one database: each reads only the supervisors it stores. */
  private Flux<Stored> mine(final String... ids) {
    final Set<String> wanted = Set.of(ids);
    return store.all().filter(stored -> wanted.contains(stored.supervisorId()));
  }

  @AfterEach
  void removeWhatTheTestsStored() {
    Flux.just("a", "b", "c").concatMap(store::delete).blockLast();
  }

  @Test
  void credentialIsStoredReplacedAndDeleted() {
    final Instant expires = Instant.parse("2030-01-01T00:00:00Z");
    StepVerifier.create(store.save(new Stored("a", "h1", null))).verifyComplete();
    StepVerifier.create(store.save(new Stored("b", "h2", expires))).verifyComplete();
    StepVerifier.create(mine("a", "b").collectList())
        .assertNext(
            all ->
                org.assertj.core.api.Assertions.assertThat(all)
                    .containsExactlyInAnyOrder(
                        new Stored("a", "h1", null), new Stored("b", "h2", expires)))
        .verifyComplete();

    StepVerifier.create(store.save(new Stored("a", "h3", expires))).verifyComplete();
    StepVerifier.create(store.delete("b")).verifyComplete();
    StepVerifier.create(mine("a", "b")).expectNext(new Stored("a", "h3", expires)).verifyComplete();
  }

  @Test
  void theAddressesComputerMayUseSurviveStoring() {
    final Stored tied = new Stored("c", "h", null, Set.of("10.0.0.1", "10.0.0.2"));
    StepVerifier.create(store.save(tied)).verifyComplete();
    StepVerifier.create(mine("c")).expectNext(tied).verifyComplete();
    StepVerifier.create(store.save(new Stored("c", "h2", null))).verifyComplete();
    StepVerifier.create(mine("c"))
        .expectNext(new Stored("c", "h2", null, Set.of()))
        .verifyComplete();
    StepVerifier.create(store.delete("c")).verifyComplete();
  }
}
