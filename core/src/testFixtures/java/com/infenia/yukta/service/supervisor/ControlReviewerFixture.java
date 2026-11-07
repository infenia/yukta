// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Short forms of {@link ControlReviewer} for tests: the shapes its extra constructors used to
 * offer.
 */
public final class ControlReviewerFixture {

  private ControlReviewerFixture() {}

  /** A ControlReviewer with the default for what the parameters leave out. */
  public static ControlReviewer create(final ObjectProvider<ExecutionSupervisor> supervisors) {
    return new ControlReviewer(supervisors, Duration.ofSeconds(5));
  }
}
