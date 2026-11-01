// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

/**
 * Tells whether this server is the one that runs workflows. With several servers sharing a database
 * only the leader admits runs; the others are standbys that take over when the leader's lease runs
 * out. A single server is always the leader.
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface LeaderElection {

  /**
   * Whether this server may admit runs now.
   *
   * @return true for the leader
   */
  boolean isLeader();

  /**
   * Whether this election recovers interrupted runs itself, when this server takes over. If so the
   * recovery at startup is skipped, because a standby that starts must not mark the runs of a
   * living leader aborted.
   *
   * @return true if recovery happens on takeover
   */
  default boolean recoversOnTakeover() {
    return false;
  }

  /**
   * Registers what to do when this server becomes the leader (also the first time, at startup).
   *
   * @param action runs on each takeover
   */
  default void onTakeover(final Runnable action) {
    // a single server never takes over from anyone
  }

  /**
   * Registers what to do when this server stops being the leader (its lease ran out or was taken).
   *
   * @param action runs on each loss
   */
  default void onLoss(final Runnable action) {
    // a single server never loses leadership
  }
}
