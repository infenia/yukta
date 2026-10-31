// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ExecutionLogStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
public abstract class ExecutionLogStoreContract {

  /** Creates the contract. */
  protected ExecutionLogStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store under test
   */
  protected abstract ExecutionLogStore create();

  @Test
  void readsBackWhatWasAppended_inOrder() {
    final ExecutionLogStore store = create();
    final ExecutionLogEvent started = ExecutionLogEvent.started("e1", "s", "w", "v");
    final ExecutionLogEvent finished =
        ExecutionLogEvent.finished("e1", "s", "w", "SUCCESS", Map.of("n", "SUCCESS"));

    store.append(started);
    store.append(finished);

    assertThat(store.readAll()).containsExactly(started, finished);
  }

  @Test
  void aRunStartKeepsWhoStartedIt() {
    final ExecutionLogStore store = create();
    final ExecutionLogEvent started =
        ExecutionLogEvent.started(
            "e1", "s", "w", "v", ExecutionLogEvent.withInitiator(Map.of("k", "v"), "yk check"));
    final ExecutionLogEvent without = ExecutionLogEvent.started("e2", "s", "w", "v");

    store.append(started);
    store.append(without);

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::initiator)
        .containsExactly("yk check", null);
  }

  @Test
  void anEmptyStoreReadsAsEmpty() {
    assertThat(create().readAll()).isEmpty();
  }

  @Test
  void replaceAllKeepsOnlyTheGivenEvents() {
    final ExecutionLogStore store = create();
    final ExecutionLogEvent first = ExecutionLogEvent.started("a", "s", "w", "v");
    final ExecutionLogEvent second = ExecutionLogEvent.started("b", "s", "w", "v");
    store.append(first);
    store.append(second);

    store.replaceAll(List.of(second));

    assertThat(store.readAll()).containsExactly(second);
  }
}
