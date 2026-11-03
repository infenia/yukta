// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
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

  /**
   * Whether a supervisor may be asked about a request. A request raised by a run that a supervisor
   * started goes only to supervisors whose scope strictly covers that supervisor's, so the
   * supervisor never waits for itself, an equal or a narrower one.
   *
   * @param candidate the supervisor
   * @param request the request
   * @return true unless the request was raised by a supervisor's own run and the candidate does not
   *     rank above that supervisor
   */
  default boolean mayReceive(final Candidate candidate, final InputRequest request) {
    return true;
  }
}
