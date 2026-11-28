// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** {@link SecretScrubber}. */
class SecretScrubberTest {

  private static final String TOKEN = "s3cr3t-t0ken/x";

  @Test
  void masksTheValueAndItsEncodings_countsTheLine_andLeavesOtherTextAlone() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final SecretScrubber scrubber = new SecretScrubber(meters);
    scrubber.record(TOKEN);
    scrubber.record("short");
    scrubber.record(null);
    final String b64 =
        Base64.getEncoder().encodeToString(TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(scrubber.scrub("auth " + TOKEN + " and " + b64 + " and s3cr3t-t0ken%2Fx"))
        .isEqualTo("auth ****** and ****** and ******");
    assertThat(scrubber.scrub("a short word")).isEqualTo("a short word");
    assertThat(meters.get("yukta.secrets.masked").counter().count()).isEqualTo(1.0);
  }

  @Test
  void scrubValue_masksStringsInNestedMapsAndLists_andKeepsWhatHasNothingToMask() {
    final SecretScrubber scrubber = new SecretScrubber(new SimpleMeterRegistry());
    final java.util.Map<String, Object> clean = java.util.Map.of("n", 1, "s", "plain");
    // nothing recorded yet: every value is handed back as it is
    assertThat(scrubber.scrubValue(clean)).isSameAs(clean);
    assertThat(scrubber.scrub("x " + TOKEN)).isEqualTo("x " + TOKEN);
    scrubber.record(TOKEN);

    final Object scrubbed =
        scrubber.scrubValue(
            java.util.Map.of(
                "error",
                "bad auth " + TOKEN,
                "items",
                java.util.List.of("ok", TOKEN, 7),
                "count",
                3));

    assertThat(scrubbed)
        .isEqualTo(
            java.util.Map.of(
                "error",
                "bad auth ******",
                "items",
                java.util.List.of("ok", "******", 7),
                "count",
                3));
    assertThat(scrubber.scrubValue(clean)).isSameAs(clean);
    assertThat(scrubber.scrubValue(java.util.List.of("a", 1))).isEqualTo(java.util.List.of("a", 1));
    assertThat(scrubber.scrubValue(null)).isNull();
    assertThat(scrubber.scrubMap(java.util.Map.of("e", TOKEN))).containsEntry("e", "******");
  }

  @Test
  void scrubValue_leavesValuesDeeperThanItWalks() {
    final SecretScrubber scrubber = new SecretScrubber(new SimpleMeterRegistry());
    scrubber.record(TOKEN);
    Object deep = TOKEN;
    for (int i = 0; i < 40; i++) {
      deep = java.util.List.of(deep);
    }

    assertThat(scrubber.scrubValue(deep)).isSameAs(deep);
  }
}
