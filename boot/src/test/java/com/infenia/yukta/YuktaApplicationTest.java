// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.*;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class YuktaApplicationTest {

  @Test
  @Timeout(60)
  void mainStartsApplicationWithoutError() {
    assertThatNoException()
        .isThrownBy(() -> YuktaApplication.main(new String[] {"--server.port=0"}));
  }
}
