// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session;

import com.infenia.yukta.service.ConflictException;
import java.util.List;

/** A session or workflow cannot be deleted while runs are still going. */
public class ActiveRunsException extends ConflictException {

  private static final long serialVersionUID = 1L;

  /** The runs that are still going. */
  private final List<String> runIds;

  /**
   * Creates the exception.
   *
   * @param runIds the runs that are still active
   * @param stillAfterForce whether they could not be stopped within the time allowed
   */
  public ActiveRunsException(final List<String> runIds, final boolean stillAfterForce) {
    super(
        runIds.size()
            + " run(s) are still active: "
            + String.join(", ", runIds)
            + (stillAfterForce
                ? ". They did not end in time after being cancelled."
                : ". Stop them first, or delete with force=true to cancel them."));
    this.runIds = List.copyOf(runIds);
  }

  /**
   * The runs that block the delete.
   *
   * @return the run ids
   */
  public List<String> runIds() {
    return runIds;
  }
}
