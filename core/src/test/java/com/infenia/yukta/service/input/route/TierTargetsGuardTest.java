// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.input.EligibleSupervisors;
import com.infenia.yukta.service.input.InputTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A supervisor the directory refuses for a request is not a target, whatever the tier says. */
class TierTargetsGuardTest {

  private static final InputRequest REQUEST =
      InputTestSupport.request("r", InputTestSupport.spec("k"));

  private static final EligibleSupervisors.Candidate ALLOWED =
      new EligibleSupervisors.Candidate("ok", new SupervisorScope.Global());

  private static final EligibleSupervisors.Candidate REFUSED =
      new EligibleSupervisors.Candidate("no", new SupervisorScope.Global());

  private static final EligibleSupervisors.Candidate ELSEWHERE =
      new EligibleSupervisors.Candidate("far", new SupervisorScope.Session("other"));

  /** Knows two supervisors and refuses one. */
  private static final EligibleSupervisors BOUND =
      new EligibleSupervisors() {
        @Override
        public List<Candidate> bound() {
          return List.of(ALLOWED, REFUSED, ELSEWHERE);
        }

        @Override
        public boolean mayReceive(final Candidate candidate, final InputRequest request) {
          return !candidate.equals(REFUSED);
        }
      };

  @Test
  void refusedSupervisorIsNotReached() {
    assertThat(TierTargets.supervisors(REQUEST, List.of("supervisor:*"), BOUND))
        .containsExactly(ALLOWED);
    assertThat(TierTargets.supervisors(REQUEST, List.of("supervisor:no", "supervisor:ok"), BOUND))
        .containsExactly(ALLOWED);
  }

  @Test
  void aboveSkipsRefusedSupervisor() {
    final Tier tier = new Tier("up", List.of("supervisor:above"), null, null, 0, 0, null, null);

    assertThat(
            TierTargets.selectors(REQUEST, tier, BOUND, List.of(new SupervisorScope.Session("x"))))
        .containsExactly("supervisor:ok");
  }
}
