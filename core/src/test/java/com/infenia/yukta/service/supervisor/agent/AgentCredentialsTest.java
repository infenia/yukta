// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentCredentialsTest {

  @Test
  void aTokenFindsItsSupervisorUntilItIsRevoked() {
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
}
