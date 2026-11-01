// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

class WaitQueueTest {

  private static final String KEY_A = "s/a";
  private static final String KEY_B = "s/b";
  private static final String EXEC_1 = "exec-1";
  private static final String EXEC_2 = "exec-2";

  private static Waiter waiter(final String key, final String executionId, final int priority) {
    return new Waiter(
        key, executionId, new AtomicInteger(priority), Sinks.one(), new AtomicReference<>());
  }

  @Test
  void add_countsPerKeyAndIndexesByExecution() {
    final WaitQueue queue = new WaitQueue();
    final Waiter first = waiter(KEY_A, EXEC_1, 0);
    queue.add(first);
    queue.add(waiter(KEY_A, EXEC_2, 0));
    queue.add(waiter(KEY_B, "exec-3", 0));

    assertThat(queue.size()).isEqualTo(3);
    assertThat(queue.count(KEY_A)).isEqualTo(2);
    assertThat(queue.count(KEY_B)).isEqualTo(1);
    assertThat(queue.count("s/none")).isZero();
    assertThat(queue.byExecution(EXEC_1)).isSameAs(first);
    assertThat(queue.byExecution("missing")).isNull();
  }

  @Test
  void add_withoutExecutionId_isCountedButNotIndexed() {
    final WaitQueue queue = new WaitQueue();
    final Waiter anonymous = waiter(KEY_A, null, 0);
    queue.add(anonymous);

    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.count(KEY_A)).isEqualTo(1);
    assertThat(queue.byExecution("null")).isNull();
    assertThat(queue.remove(anonymous)).isTrue();
    assertThat(queue.size()).isZero();
  }

  @Test
  void remove_decrementsKeyCountAndDropsExecutionIndex() {
    final WaitQueue queue = new WaitQueue();
    final Waiter first = waiter(KEY_A, EXEC_1, 0);
    final Waiter second = waiter(KEY_A, EXEC_2, 0);
    queue.add(first);
    queue.add(second);

    assertThat(queue.remove(first)).isTrue();
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.count(KEY_A)).isEqualTo(1);
    assertThat(queue.byExecution(EXEC_1)).isNull();
    assertThat(queue.byExecution(EXEC_2)).isSameAs(second);

    assertThat(queue.remove(second)).isTrue();
    assertThat(queue.size()).isZero();
    assertThat(queue.count(KEY_A)).isZero();
    assertThat(queue.groups()).isEmpty();
  }

  @Test
  void remove_ofWaiterNeverAdded_isFalseAndChangesNothing() {
    final WaitQueue queue = new WaitQueue();
    queue.add(waiter(KEY_A, EXEC_1, 0));

    // no group exists for this priority
    assertThat(queue.remove(waiter(KEY_A, "exec-x", 5))).isFalse();
    // a group exists for this priority, but the waiter is not in it
    assertThat(queue.remove(waiter(KEY_A, "exec-y", 0))).isFalse();

    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.count(KEY_A)).isEqualTo(1);
    assertThat(queue.byExecution(EXEC_1)).isNotNull();
  }

  @Test
  void remove_keepsPriorityGroupWhileItStillHasWaiters() {
    final WaitQueue queue = new WaitQueue();
    final Waiter first = waiter(KEY_A, EXEC_1, 2);
    final Waiter second = waiter(KEY_A, EXEC_2, 2);
    queue.add(first);
    queue.add(second);

    assertThat(queue.remove(first)).isTrue();

    assertThat(queue.groups()).hasSize(1);
    assertThat(queue.groups().iterator().next()).containsExactly(second);
  }

  @Test
  void groups_listPriorityGroupsHighestFirstAndOldestFirstWithin() {
    final WaitQueue queue = new WaitQueue();
    final Waiter low = waiter(KEY_A, "low", 0);
    final Waiter highOld = waiter(KEY_A, "high-old", 9);
    final Waiter highNew = waiter(KEY_B, "high-new", 9);
    queue.add(low);
    queue.add(highOld);
    queue.add(highNew);

    final List<Waiter> ordered = new ArrayList<>();
    queue.groups().forEach(ordered::addAll);

    assertThat(ordered).containsExactly(highOld, highNew, low);
  }

  @Test
  void drain_handsEveryWaiterToActionAndEmptiesTheQueue() {
    final WaitQueue queue = new WaitQueue();
    final Waiter low = waiter(KEY_A, "low", 0);
    final Waiter high = waiter(KEY_B, "high", 4);
    final Waiter anonymous = waiter(KEY_A, null, 4);
    queue.add(low);
    queue.add(high);
    queue.add(anonymous);

    final List<Waiter> drained = new ArrayList<>();
    queue.drain(drained::add);

    assertThat(drained).containsExactly(high, anonymous, low);
    assertThat(queue.size()).isZero();
    assertThat(queue.count(KEY_A)).isZero();
    assertThat(queue.count(KEY_B)).isZero();
    assertThat(queue.byExecution("high")).isNull();
    assertThat(queue.groups()).isEmpty();
  }

  @Test
  void drain_ofEmptyQueue_doesNothing() {
    final WaitQueue queue = new WaitQueue();
    final List<Waiter> drained = new ArrayList<>();

    queue.drain(drained::add);

    assertThat(drained).isEmpty();
    assertThat(queue.size()).isZero();
  }
}
