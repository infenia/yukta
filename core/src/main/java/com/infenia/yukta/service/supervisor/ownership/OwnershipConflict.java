// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

/**
 * Two supervisors would own the same thing.
 *
 * @param supervisorId one supervisor
 * @param otherSupervisorId the other
 * @param disputed what both would own, in words ("node build.test", "the run level of release")
 */
public record OwnershipConflict(String supervisorId, String otherSupervisorId, String disputed) {

  /**
   * The text for an error body.
   *
   * @return for example {@code node build.test of release is already owned by release-guard}
   */
  public String message() {
    return disputed + " already owned by " + otherSupervisorId;
  }
}
