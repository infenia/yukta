// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link RunByteCap}. */
class RunByteCapTest {

  private static final String RUN_1 = "run-1";
  private static final String RUN_2 = "run-2";
  private static final String NODE_A = "node-a";
  private static final String NODE_B = "node-b";

  @Test
  void maxIsAtLeastOneByte() {
    assertThat(new RunByteCap(0).max()).isEqualTo(1);
    assertThat(new RunByteCap(-5).max()).isEqualTo(1);
    assertThat(new RunByteCap(64).max()).isEqualTo(64);
  }

  @Test
  void fitsCountsBatchesUntilTheCapIsReached() {
    final RunByteCap cap = new RunByteCap(10);

    assertThat(cap.fits(RUN_1, List.of("abcd", "efg"))).isTrue();
    assertThat(cap.fits(RUN_1, List.of("hijk"))).isFalse();
  }

  @Test
  void fitsDoesNotCountBatchThatDoesNotFit() {
    final RunByteCap cap = new RunByteCap(10);

    assertThat(cap.fits(RUN_1, List.of("abcdefghijk"))).isFalse();
    assertThat(cap.fits(RUN_1, List.of("abcdefghij"))).isTrue();
  }

  @Test
  void fitsKeepsEachRunsCountSeparate() {
    final RunByteCap cap = new RunByteCap(4);

    assertThat(cap.fits(RUN_1, List.of("abcd"))).isTrue();
    assertThat(cap.fits(RUN_2, List.of("abcd"))).isTrue();
    assertThat(cap.fits(RUN_1, List.of("e"))).isFalse();
  }

  @Test
  void firstCappedIsTrueOnlyOncePerNodeOfRun() {
    final RunByteCap cap = new RunByteCap(1);

    assertThat(cap.firstCapped(RUN_1, NODE_A)).isTrue();
    assertThat(cap.firstCapped(RUN_1, NODE_A)).isFalse();
    assertThat(cap.firstCapped(RUN_1, NODE_B)).isTrue();
    assertThat(cap.firstCapped(RUN_2, NODE_A)).isTrue();
  }

  @Test
  void forgetDropsTheGivenRunsOnly() {
    final RunByteCap cap = new RunByteCap(2);
    cap.fits(RUN_1, List.of("ab"));
    cap.fits(RUN_2, List.of("ab"));
    cap.firstCapped(RUN_1, NODE_A);
    cap.firstCapped(RUN_2, NODE_A);

    cap.forget(Set.of(RUN_1), false);

    assertThat(cap.fits(RUN_1, List.of("ab"))).isTrue();
    assertThat(cap.fits(RUN_2, List.of("ab"))).isFalse();
    assertThat(cap.firstCapped(RUN_1, NODE_A)).isTrue();
    assertThat(cap.firstCapped(RUN_2, NODE_A)).isFalse();
  }

  @Test
  void forgetWithAllButDropsEveryRunNotGiven() {
    final RunByteCap cap = new RunByteCap(2);
    cap.fits(RUN_1, List.of("ab"));
    cap.fits(RUN_2, List.of("ab"));
    cap.firstCapped(RUN_1, NODE_A);
    cap.firstCapped(RUN_2, NODE_A);

    cap.forget(Set.of(RUN_1), true);

    assertThat(cap.fits(RUN_1, List.of("ab"))).isFalse();
    assertThat(cap.fits(RUN_2, List.of("ab"))).isTrue();
    assertThat(cap.firstCapped(RUN_1, NODE_A)).isFalse();
    assertThat(cap.firstCapped(RUN_2, NODE_A)).isTrue();
  }
}
