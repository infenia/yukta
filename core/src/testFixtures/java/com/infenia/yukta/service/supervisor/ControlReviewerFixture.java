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
    return pipeline(supervisors, Duration.ofSeconds(5));
  }

  /**
   * A reviewer that asks every matching supervisor, with a chosen timeout; see {@link
   * PipelineReviewer}.
   *
   * @param supervisors the supervisors to ask
   * @param timeout longest a supervisor may take to answer
   * @return the reviewer
   */
  public static ControlReviewer pipeline(
      final ObjectProvider<ExecutionSupervisor> supervisors, final Duration timeout) {
    return new PipelineReviewer(supervisors, timeout);
  }

  /**
   * A reviewer that asks every matching supervisor, with the review timeout of the settings.
   *
   * @param supervisors the supervisors to ask
   * @param properties the settings
   * @return the reviewer
   */
  public static ControlReviewer pipeline(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final com.infenia.yukta.config.YuktaProperties properties) {
    return pipeline(supervisors, properties.supervisor().reviewTimeout());
  }
}
