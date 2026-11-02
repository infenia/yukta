// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.SupervisorScope;
import java.util.List;

/**
 * The supervisors that are attached now, so the broker can tell which of them a tier reaches for a
 * given request. Without it no supervisor is known and a tier made only of supervisors has no
 * target.
 *
 * @since 0.1.0-alpha.4
 */
@FunctionalInterface
public interface EligibleSupervisors {

  /** Knows no supervisor. */
  EligibleSupervisors NONE = List::of;

  /**
   * A supervisor that is attached.
   *
   * @param id the supervisor id
   * @param scope what it watches
   */
  record Candidate(String id, SupervisorScope scope) {}

  /**
   * Every attached supervisor.
   *
   * @return the supervisors, in no particular order
   */
  List<Candidate> bound();
}
