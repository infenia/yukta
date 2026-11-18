// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A request body above {@code spring.codec.max-in-memory-size} is refused with 413. */
@E2eTest
@NoArgsConstructor
class RequestSizeLimitE2eTest {

  private static int post(final Api api, final int bytes) {
    return api.call("POST", "/api/sessions/validate", "{\"x\":\"" + "a".repeat(bytes) + "\"}")
        .status();
  }

  @Test
  void oversizedBodyIsAnswered413(final Api api) {
    assertThat(post(api, 2 * 1024 * 1024)).isEqualTo(413);
  }

  @Test
  void normalBodyIsNotRefusedForSize(final Api api) {
    assertThat(post(api, 100)).isNotEqualTo(413);
  }
}
