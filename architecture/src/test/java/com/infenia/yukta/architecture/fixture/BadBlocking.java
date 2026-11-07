// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

import reactor.core.publisher.Mono;

/** Blocks a Mono; the self-test expects the rule to object. */
public final class BadBlocking {
  private BadBlocking() {}

  /** Blocks. */
  public static Integer get() {
    return Mono.just(1).block();
  }
}
