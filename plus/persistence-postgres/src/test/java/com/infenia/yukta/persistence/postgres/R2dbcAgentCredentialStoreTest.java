// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.supervisor.agent.AgentCredentialStore.Stored;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
class R2dbcAgentCredentialStoreTest extends PostgresTestSupport {

  private final R2dbcAgentCredentialStore store = new R2dbcAgentCredentialStore(db);

  @Test
  void aCredentialIsStoredReplacedAndDeleted() {
    final Instant expires = Instant.parse("2030-01-01T00:00:00Z");
    StepVerifier.create(store.save(new Stored("a", "h1", null))).verifyComplete();
    StepVerifier.create(store.save(new Stored("b", "h2", expires))).verifyComplete();
    StepVerifier.create(store.all().collectList())
        .assertNext(
            all ->
                org.assertj.core.api.Assertions.assertThat(all)
                    .containsExactlyInAnyOrder(
                        new Stored("a", "h1", null), new Stored("b", "h2", expires)))
        .verifyComplete();

    StepVerifier.create(store.save(new Stored("a", "h3", expires))).verifyComplete();
    StepVerifier.create(store.delete("b")).verifyComplete();
    StepVerifier.create(store.all()).expectNext(new Stored("a", "h3", expires)).verifyComplete();
  }

  @Test
  void theAddressesAComputerMayUseSurviveStoring() {
    final Stored tied = new Stored("c", "h", null, java.util.Set.of("10.0.0.1", "10.0.0.2"));
    StepVerifier.create(store.save(tied)).verifyComplete();
    StepVerifier.create(store.all()).expectNext(tied).verifyComplete();
    StepVerifier.create(store.save(new Stored("c", "h2", null))).verifyComplete();
    StepVerifier.create(store.all())
        .expectNext(new Stored("c", "h2", null, java.util.Set.of()))
        .verifyComplete();
    StepVerifier.create(store.delete("c")).verifyComplete();
  }
}
