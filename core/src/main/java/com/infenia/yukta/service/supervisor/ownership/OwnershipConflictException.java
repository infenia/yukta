// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import com.infenia.yukta.service.ConflictException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A supervisor, an assignment or a workflow definition would give one unit to two supervisors.
 * Answered as {@code 409 OWNERSHIP_CONFLICT}.
 */
public class OwnershipConflictException extends ConflictException {

  private static final long serialVersionUID = 1L;

  /** The error code clients see. */
  public static final String CODE = "OWNERSHIP_CONFLICT";

  /** The overlaps, one per other supervisor. */
  private final transient List<OwnershipConflict> conflicts;

  /**
   * Creates the exception.
   *
   * @param conflicts the overlaps, at least one
   */
  public OwnershipConflictException(final List<OwnershipConflict> conflicts) {
    super(conflicts.stream().map(OwnershipConflict::message).collect(Collectors.joining("; ")));
    this.conflicts = List.copyOf(conflicts);
  }

  /**
   * What is in dispute.
   *
   * @return the overlaps
   */
  public List<OwnershipConflict> getConflicts() {
    return conflicts;
  }
}
