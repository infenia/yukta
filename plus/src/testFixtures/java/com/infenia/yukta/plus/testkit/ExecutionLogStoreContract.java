// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.control.StartOptions;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ExecutionLogStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // run ids and statuses read better inline
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

    assertThat(store.readAll().collectList().block()).containsExactly(started, finished);
  }

  @Test
  void runStartKeepsWhoStartedIt() {
    final ExecutionLogStore store = create();
    final ExecutionLogEvent started =
        ExecutionLogEvent.started(
            "e1", "s", "w", "v", StartOptions.withInitiator(Map.of("k", "v"), "yk check"));
    final ExecutionLogEvent without = ExecutionLogEvent.started("e2", "s", "w", "v");

    store.append(started);
    store.append(without);

    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::initiator)
        .containsExactly("yk check", null);
  }

  @Test
  void purgeEndedBefore_removesEndedRunsOnly() {
    final ExecutionLogStore store = create();
    final ExecutionLogEvent open = ExecutionLogEvent.started("open", "s", "w", "v");
    final ExecutionLogEvent aborted = ExecutionLogEvent.started("aborted", "s", "w", "v");
    store.append(ExecutionLogEvent.started("done", "s", "w", "v"));
    store.append(ExecutionLogEvent.finished("done", "s", "w", "SUCCESS", Map.of()));
    store.append(aborted);
    store.append(ExecutionLogEvent.aborted(aborted));
    store.append(open);
    store.append(ExecutionLogEvent.limitsChanged(1, 1));

    final List<String> gone =
        store.purgeEndedBefore(java.time.Instant.now().plusSeconds(60)).block();

    assertThat(gone).containsExactlyInAnyOrder("done", "aborted");
    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("open", ExecutionLogEvent.SCHEDULER);
  }

  @Test
  void purgeEndedBefore_removesEveryEndedRunEvenWhenThereAreMoreThanOneBatch() {
    final ExecutionLogStore store = create();
    final int runs = 250;
    for (int i = 0; i < runs; i++) {
      final String id = "run-" + i;
      store.append(ExecutionLogEvent.started(id, "s", "w", "v"));
      store.append(ExecutionLogEvent.finished(id, "s", "w", "SUCCESS", Map.of()));
    }

    final List<String> gone =
        store.purgeEndedBefore(java.time.Instant.now().plusSeconds(60)).block();

    assertThat(gone).hasSize(runs).doesNotHaveDuplicates();
    assertThat(store.readAll().collectList().block()).isEmpty();
  }

  @Test
  void purgeEndedBefore_keepsRunsThatEndedAfterTheCutoff() {
    final ExecutionLogStore store = create();
    store.append(ExecutionLogEvent.started("done", "s", "w", "v"));
    store.append(ExecutionLogEvent.finished("done", "s", "w", "SUCCESS", Map.of()));

    assertThat(store.purgeEndedBefore(java.time.Instant.now().minusSeconds(60)).block()).isEmpty();
    assertThat(store.readAll().collectList().block()).hasSize(2);
  }

  @Test
  void anEmptyStoreReadsAsEmpty() {
    assertThat(create().readAll().collectList().block()).isEmpty();
  }

  @Test
  void applyRecoveryDropsRunsTrimsTheSchedulerAndAppendsTheEnds() {
    final ExecutionLogStore store = create();
    final ExecutionLogEvent interrupted = ExecutionLogEvent.started("live", "s", "w", "v");
    store.append(ExecutionLogEvent.started("old", "s", "w", "v"));
    store.append(ExecutionLogEvent.limitsChanged(1, 1));
    store.append(interrupted);
    store.append(ExecutionLogEvent.limitsChanged(2, 2));
    store.append(ExecutionLogEvent.limitsChanged(3, 3));

    store
        .applyRecovery(java.util.Set.of("old"), 2, List.of(ExecutionLogEvent.aborted(interrupted)))
        .block();

    final List<ExecutionLogEvent> left = store.readAll().collectList().block();
    assertThat(left)
        .extracting(ExecutionLogEvent::executionId, ExecutionLogEvent::type)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("live", ExecutionLogEvent.Type.RUN_STARTED),
            org.assertj.core.groups.Tuple.tuple(
                ExecutionLogEvent.SCHEDULER, ExecutionLogEvent.Type.LIMITS_CHANGED),
            org.assertj.core.groups.Tuple.tuple(
                ExecutionLogEvent.SCHEDULER, ExecutionLogEvent.Type.LIMITS_CHANGED),
            org.assertj.core.groups.Tuple.tuple("live", ExecutionLogEvent.Type.RUN_ABORTED));
    // the two newest scheduler changes stay
    assertThat(left.get(1).nodeStatuses()).containsEntry(ExecutionLogEvent.MAX_CONCURRENT, "2");
    assertThat(store.forExecution("old").collectList().block()).isEmpty();
  }

  @Test
  void applyRecoveryWithNothingToDo_changesNothing() {
    final ExecutionLogStore store = create();
    store.append(ExecutionLogEvent.started("a", "s", "w", "v"));
    store.append(ExecutionLogEvent.limitsChanged(1, 1));

    store.applyRecovery(java.util.Set.of(), 5, List.of()).block();

    assertThat(store.readAll().collectList().block()).hasSize(2);
  }

  @Test
  void queriesByRunHeaderAndType() {
    final ExecutionLogStore store = create();
    store.append(ExecutionLogEvent.started("a", "s1", "w1", "v"));
    store.append(ExecutionLogEvent.started("b", "s2", "w2", "v"));
    store.append(ExecutionLogEvent.finished("a", "s1", "w1", "SUCCESS", Map.of()));

    assertThat(store.forExecution("a").collectList().block())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    assertThat(store.header("b").blockOptional())
        .contains(new ExecutionLogStore.RunHeader("s2", "w2"));
    assertThat(store.header("nope").blockOptional()).isEmpty();
    assertThat(
            store
                .ofType(ExecutionLogEvent.Type.RUN_FINISHED, java.time.Instant.EPOCH)
                .collectList()
                .block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("a");
  }
}
