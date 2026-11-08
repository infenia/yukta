// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.service.ConflictException;
import org.jspecify.annotations.Nullable;

/**
 * A person sent a command for something a supervisor owns, and the supervisor does not let people
 * send that command (its {@code operatorAccess}), or nobody owns it and the default supervisor is
 * off. Answered as {@code 409 CONTROLLED_BY_SUPERVISOR}.
 */
public class ControlledBySupervisorException extends ConflictException {

  private static final long serialVersionUID = 1L;

  /** The error code clients see. */
  public static final String CODE = "CONTROLLED_BY_SUPERVISOR";

  /** The owning supervisor, or null when nobody owns the unit. */
  private final transient @Nullable String supervisorId;

  /**
   * Creates the exception.
   *
   * @param supervisorId the supervisor that owns the unit, or {@code null} when nobody does
   * @param message why, safe to show to API clients
   */
  public ControlledBySupervisorException(
      final @Nullable String supervisorId, final String message) {
    super(message);
    this.supervisorId = supervisorId;
  }

  /**
   * The supervisor that owns the unit.
   *
   * @return its id, or {@code null} when nobody owns it
   */
  public @Nullable String getSupervisorId() {
    return supervisorId;
  }
}
