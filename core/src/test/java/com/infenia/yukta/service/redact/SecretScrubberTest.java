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
}
