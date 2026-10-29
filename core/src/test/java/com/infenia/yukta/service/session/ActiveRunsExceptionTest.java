// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests the message and the run ids of a refused session or workflow delete. */
@NoArgsConstructor
class ActiveRunsExceptionTest {

  @Test
  void message_suggestsForceBeforeTheDeleteAndSaysWhySecondTimeItDidNot() {
    final ActiveRunsException refused = new ActiveRunsException(List.of("e1", "e2"), false);
    final ActiveRunsException stuck = new ActiveRunsException(List.of("e3"), true);

    assertThat(refused.getMessage())
        .isEqualTo(
            "2 run(s) are still active: e1, e2. Stop them first, or delete with force=true"
                + " to cancel them.");
    assertThat(stuck.getMessage())
        .isEqualTo(
            "1 run(s) are still active: e3. They did not end in time after being cancelled.");
  }

  @Test
  void runIds_isCopiedAndReadOnly() {
    final List<String> ids = new ArrayList<>(List.of("e1"));
    final ActiveRunsException error = new ActiveRunsException(ids, false);
    ids.add("e2");

    assertThat(error.runIds()).containsExactly("e1");
    assertThatThrownBy(() -> error.runIds().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
