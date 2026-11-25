// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.DedicatedServer;
import com.infenia.yukta.e2e.support.PostgresDatabase;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * High availability on one database ({@code yukta.ha.mode=postgres}): of two servers exactly one is
 * the leader, and when it stops the other takes the lease over (a higher fencing number).
 */
@EnabledIfSystemProperty(named = "yukta.persistence", matches = "postgres")
// A server closed in this JVM stops the HTTP of every other one, so it runs in a JVM of its own
// (the ownJvmTest task); the shared server of the other tests would be gone.
@Tag("own-jvm")
@ExpectedErrorLog(
    sometimes = {
      "ERROR com.infenia.yukta.persistence.postgres.Fencing - ",
      "ERROR com.infenia.yukta.persistence.postgres.R2dbcExecutionLogStore - ",
    })
@NoArgsConstructor
class PostgresHaE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(60);
  private static final int OK = 200;
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // any failure: the server is not (yet) there
  private static boolean leader(final Api api) {
    try {
      final var reply = api.call("GET", "/actuator/metrics/yukta.leader", null);
      if (reply.status() != OK) {
        return false;
      }
      final JsonNode value = JSON.readTree(reply.body()).path("measurements").path(0).path("value");
      return value.asDouble() == 1.0;
    } catch (RuntimeException unreachable) {
      return false;
    }
  }

  @Test
  @SuppressWarnings("PMD.CloseResource") // the servers stop with the JVM
  void oneServerLeadsAndTheStandbyTakesOverWhenItStops() {
    final DedicatedServer first =
        DedicatedServer.start(
            true, "yukta.ha.mode=postgres", "yukta.ha.lease=3s", "yukta.ha.instance-id=e2e-first");
    final DedicatedServer second =
        DedicatedServer.start(
            true, "yukta.ha.mode=postgres", "yukta.ha.lease=3s", "yukta.ha.instance-id=e2e-second");
    final Api one = Api.toServer(first.baseUrl(), first.key(ApiRole.ADMIN));
    final Api two = Api.toServer(second.baseUrl(), second.key(ApiRole.ADMIN));

    await().atMost(WAIT).until(() -> leader(one) || leader(two));
    assertThat(leader(one) && leader(two)).as("two leaders").isFalse();
    final boolean firstLeads = leader(one);

    final String leading = firstLeads ? "e2e-first" : "e2e-second";
    final String standing = firstLeads ? "e2e-second" : "e2e-first";
    final String before = PostgresDatabase.lease();
    assertThat(before).startsWith(leading + ":");
    final long fencing = Long.parseLong(before.substring(before.indexOf(':') + 1));

    // Stopping a server in this JVM also takes down the HTTP of the others (shared Reactor
    // resources), so the standby is checked through the lease row, not through its API.
    (firstLeads ? first : second).stop();

    await()
        .atMost(WAIT)
        .until(() -> {
          final String now = PostgresDatabase.lease();
          return now.startsWith(standing + ":")
              && Long.parseLong(now.substring(now.indexOf(':') + 1)) > fencing;
        });
  }
}
