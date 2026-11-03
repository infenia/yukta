// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.service.input.EligibleSupervisors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tells the input broker which supervisors are attached, from {@link AttachedSupervisors}.
 *
 * @since 0.1.0-alpha.4
 */
@Component
public class AttachedSupervisorsDirectory implements EligibleSupervisors {

  /** The attached supervisors. */
  private final AttachedSupervisors attached;

  /**
   * Creates the directory.
   *
   * @param attached the attached supervisors
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // a shared Spring bean, held on purpose
  public AttachedSupervisorsDirectory(final AttachedSupervisors attached) {
    this.attached = attached;
  }

  @Override
  public List<Candidate> bound() {
    return attached.stream().map(s -> new Candidate(s.id(), s.scope())).toList();
  }
}
