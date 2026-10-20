// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

/**
 * The election of a single server: it is always the leader. Registered by {@link
 * com.infenia.yukta.config.DefaultsAutoConfiguration} unless another {@link LeaderElection} exists.
 */
public class SingleServerLeader implements LeaderElection {

  /** Creates the election. */
  public SingleServerLeader() {
    super();
  }

  @Override
  public boolean isLeader() {
    return true;
  }
}
