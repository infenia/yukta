// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.infenia.yukta.service.leader.LeaderElection;
import org.junit.jupiter.api.Test;

/**
 * What every {@link LeaderElection} must do. Extend it in the test of an implementation and return
 * an election that is the leader from {@link #createLeader()}.
 */
public abstract class LeaderElectionContract {

  /** Creates the contract. */
  protected LeaderElectionContract() {
    super();
  }

  /**
   * An election that currently holds leadership.
   *
   * @return the election under test
   */
  protected abstract LeaderElection createLeader();

  @Test
  void theLeaderSaysSo_andKeepsSayingSoWhileNothingChanges() {
    final LeaderElection election = createLeader();

    assertThat(election.isLeader()).isTrue();
    assertThat(election.isLeader()).isTrue();
  }

  @Test
  void registeringTakeoverCallbackNeverFails() {
    final LeaderElection election = createLeader();

    assertThatCode(() -> election.onTakeover(() -> {})).doesNotThrowAnyException();
    assertThatCode(election::recoversOnTakeover).doesNotThrowAnyException();
  }
}
