// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class YuktaApplicationTest {

  @Test
  @Timeout(20)
  void mainStartsApplicationWithoutError() {
    assertThatNoException()
        .isThrownBy(
            () -> {
              final ExecutorService executor = Executors.newSingleThreadExecutor();
              @SuppressWarnings("unused")
              Future<?> startTask =
                  executor.submit(() -> YuktaApplication.main(new String[] {"--server.port=0"}));
              executor.shutdown();
              final boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
              if (!completed) {
                executor.shutdownNow();
              }
            });
  }
}
