// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** The election of a single server: it is always the leader. */
@Component
@ConditionalOnExpression("!'postgres'.equals('${yukta.ha.mode:}')")
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
