// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Hooks;

/** Records the signals Reactor drops because no subscriber could take them. */
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
final class ReactorProbe implements Probe {

  private final List<String> seen = new CopyOnWriteArrayList<>();

  @Override
  public void start() {
    seen.clear();
    Hooks.onErrorDropped(error -> seen.add("dropped error: " + error));
    Hooks.onNextDropped(value -> seen.add("dropped next: " + value));
  }

  @Override
  public List<String> stop() {
    Hooks.resetOnErrorDropped();
    Hooks.resetOnNextDropped();
    return List.copyOf(seen);
  }
}
