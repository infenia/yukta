// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class AgentCredentialsTest {

  @Test
  void tokenFindsItsSupervisorUntilItIsRevoked() {
    final AgentCredentials credentials = new AgentCredentials();
    final AgentCredentials.Issued issued = credentials.issue("a1", null);

    assertThat(issued.token()).startsWith(AgentCredentials.PREFIX);
    assertThat(issued.expiresAt()).isNull();
    assertThat(credentials.authenticate(issued.token())).contains("a1");
    assertThat(credentials.has("a1")).isTrue();
    assertThat(credentials.revoke("a1")).isTrue();
    assertThat(credentials.authenticate(issued.token())).isEmpty();
    assertThat(credentials.revoke("a1")).isFalse();
    assertThat(credentials.has("a1")).isFalse();
  }

  @Test
  void metadataShowsIssueExpiryAddressesAndLastUseButNeverTheSecret() {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    final AgentCredentials credentials = new AgentCredentials(clock);
    assertThat(credentials.metadata("a1")).isEmpty();
    final String token =
        credentials.issue("a1", java.time.Duration.ofHours(2), Set.of("10.0.0.5")).token();

    final AgentCredentials.Metadata fresh = credentials.metadata("a1").orElseThrow();
    assertThat(fresh.issuedAt()).isEqualTo(clock.instant());
    assertThat(fresh.expiresAt()).isEqualTo(clock.instant().plusSeconds(7200));
    assertThat(fresh.addresses()).containsExactly("10.0.0.5");
    assertThat(fresh.lastUsedAt()).isNull();
    assertThat(fresh.toString()).doesNotContain(token);

    credentials.authenticate(token, "10.0.0.5");
    assertThat(credentials.metadata("a1").orElseThrow().lastUsedFrom()).isEqualTo("10.0.0.5");
    credentials.revoke("a1");
    assertThat(credentials.metadata("a1")).isEmpty();
  }

  @Test
  void issuingAgainRotatesTheCredential() {
    final AgentCredentials credentials = new AgentCredentials();
    final String first = credentials.issue("a1", null).token();
    final String second = credentials.issue("a1", null).token();

    assertThat(second).isNotEqualTo(first);
    assertThat(credentials.authenticate(first)).isEmpty();
    assertThat(credentials.authenticate(second)).contains("a1");
  }

  @Test
  void anExpiredTokenAndForeignTextAreRefused() {
    final Instant start = Instant.parse("2026-11-08T10:00:00Z");
    final Instant[] now = {start};
    final Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(final java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    final AgentCredentials credentials = new AgentCredentials(clock);
    final AgentCredentials.Issued issued = credentials.issue("a1", Duration.ofMinutes(5));
    assertThat(issued.expiresAt()).isEqualTo(start.plus(Duration.ofMinutes(5)));
    assertThat(credentials.authenticate(issued.token())).contains("a1");

    now[0] = start.plus(Duration.ofMinutes(6));
    assertThat(credentials.authenticate(issued.token())).isEmpty();
    assertThat(credentials.authenticate("nope")).isEmpty();
    assertThat(credentials.authenticate(null)).isEmpty();
    assertThat(credentials.authenticate(AgentCredentials.PREFIX + "unknown")).isEmpty();
  }

  /** A store in a map, with the writes visible. */
  private static final class MapStore implements AgentCredentialStore {
    private final java.util.Map<String, Stored> rows =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public reactor.core.publisher.Flux<Stored> all() {
      return reactor.core.publisher.Flux.fromIterable(rows.values());
    }

    @Override
    public reactor.core.publisher.Mono<Void> save(final Stored credential) {
      rows.put(credential.supervisorId(), credential);
      return reactor.core.publisher.Mono.empty();
    }

    @Override
    public reactor.core.publisher.Mono<Void> delete(final String supervisorId) {
      rows.remove(supervisorId);
      return reactor.core.publisher.Mono.empty();
    }
  }

  @Test
  void storedCredentialSurvivesRestartUntilItIsRevoked() {
    final MapStore store = new MapStore();
    final AgentCredentials first = new AgentCredentials();
    first.setStore(store);
    final String token = first.issue("a1", null).token();
    assertThat(store.rows).containsKey("a1");

    final AgentCredentials restarted = new AgentCredentials();
    restarted.setStore(store);
    assertThat(restarted.authenticate(token)).isEmpty();
    restarted.restoreOnReady();
    assertThat(restarted.authenticate(token)).contains("a1");

    assertThat(restarted.revoke("a1")).isTrue();
    assertThat(store.rows).isEmpty();
  }

  @Test
  void anExpiredStoredCredentialIsNotLoadedAndFailingStoreDoesNotBreakIssuing() {
    final MapStore store = new MapStore();
    store.rows.put(
        "old", new AgentCredentialStore.Stored("old", "h", Instant.parse("2020-01-01T00:00:00Z")));
    final AgentCredentials credentials = new AgentCredentials();
    credentials.setStore(store);
    credentials.restoreOnReady();
    assertThat(credentials.has("old")).isFalse();

    final AgentCredentialStore broken =
        new AgentCredentialStore() {
          @Override
          public reactor.core.publisher.Flux<Stored> all() {
            return reactor.core.publisher.Flux.error(new IllegalStateException("down"));
          }

          @Override
          public reactor.core.publisher.Mono<Void> save(final Stored credential) {
            return reactor.core.publisher.Mono.error(new IllegalStateException("down"));
          }

          @Override
          public reactor.core.publisher.Mono<Void> delete(final String supervisorId) {
            return reactor.core.publisher.Mono.error(new IllegalStateException("down"));
          }
        };
    final AgentCredentials offline = new AgentCredentials();
    offline.setStore(broken);
    offline.restoreOnReady();
    final String token = offline.issue("a", null).token();
    assertThat(offline.authenticate(token)).contains("a");
    assertThat(offline.revoke("a")).isTrue();
  }

  @Test
  void credentialTiedToAddressesWorksOnlyFromThem() {
    final AgentCredentials credentials = new AgentCredentials();
    final String token =
        credentials.issue("a1", null, java.util.Set.of("10.0.0.5", "10.0.0.6")).token();

    assertThat(credentials.authenticate(token, "10.0.0.5")).contains("a1");
    assertThat(credentials.authenticate(token, "10.0.0.6")).contains("a1");
    assertThat(credentials.authenticate(token, "10.0.0.9")).isEmpty();
    assertThat(credentials.authenticate(token, null)).isEmpty();
    assertThat(credentials.authenticate(token)).isEmpty();
    // without addresses it works from anywhere
    final String open = credentials.issue("a2", null).token();
    assertThat(credentials.authenticate(open, "203.0.113.1")).contains("a2");
  }

  @Test
  void revokingAndRotatingEndTheAgentStreams() {
    final AgentHub hub = new AgentHub();
    final AgentCredentials credentials = new AgentCredentials();
    credentials.setHub(hub);

    StepVerifier.create(hub.credentialChanges())
        .then(
            () -> {
              credentials.issue("a1", null);
              credentials.issue("a1", null);
              credentials.revoke("a1");
            })
        .expectNext("a1", "a1")
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void withoutStoreRestoreDoesNothingAndUnknownRevokeTouchesNoStore() {
    StepVerifier.create(new AgentCredentials().restore()).verifyComplete();

    final MapStore store = new MapStore();
    store.rows.put("other", new AgentCredentialStore.Stored("other", "h", null));
    final AgentCredentials credentials = new AgentCredentials();
    credentials.setStore(store);
    assertThat(credentials.revoke("a1")).isFalse();
    assertThat(store.rows).containsKey("other");
  }

  @Test
  void twoStoredRowsWithOneHashAreCutOnce() {
    final MapStore store = new MapStore();
    store.rows.put("x", new AgentCredentialStore.Stored("x", "same", null));
    store.rows.put("y", new AgentCredentialStore.Stored("y", "same", null));
    final AgentCredentials credentials = new AgentCredentials();
    credentials.setStore(store);
    StepVerifier.create(credentials.restore()).verifyComplete();

    // the first revoke removes the shared hash, so the second finds nothing left to cut
    assertThat(credentials.revoke("x")).isTrue();
    assertThat(credentials.revoke("y")).isFalse();
  }

  @Test
  void unknownHashAlgorithmIsIllegalState() {
    assertThatThrownBy(() -> AgentCredentials.hash("no-such-digest", "t"))
        .isInstanceOf(IllegalStateException.class);
  }
}
