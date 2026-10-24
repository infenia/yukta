// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class RemoteFailureExceptionTest {

  @Test
  void keepsTheMessageAndTheRemoteClassNames() {
    final RemoteFailureException error =
        new RemoteFailureException("w1 failed", Set.of("java.io.IOException"));

    assertThat(error).hasMessage("w1 failed");
    assertThat(error.remoteTypes()).containsExactly("java.io.IOException");
  }
}
