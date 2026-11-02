// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An {@link ExecutionSupervisor}'s verdict on a control command: allow, deny, or require approval.
 *
 * <p>A decision that requires approval is not {@link #allowed()}; callers must check {@link
 * #approval()} to tell it from a denial. The server then parks the command and raises an {@code
 * APPROVAL} input request; the command runs only once that request is approved.
 *
 * @param allowed true to let the command run
 * @param reason why it was denied or needs approval; {@code null} when allowed
 * @param approval what approval is needed, or {@code null} for an allow or a denial
 */
public record Decision(boolean allowed, @Nullable String reason, @Nullable ApprovalNeed approval) {

  /** The command may run. */
  public static final Decision ALLOW = new Decision(true, null);

  /**
   * Checks that an allowed decision does not also ask for approval.
   *
   * @param allowed true to let the command run
   * @param reason why it was denied or needs approval
   * @param approval what approval is needed, or {@code null}
   * @throws IllegalArgumentException if an allowed decision names an approval
   */
  public Decision {
    if (allowed && approval != null) {
      throw new IllegalArgumentException("An allowed decision needs no approval");
    }
  }

  /**
   * An allow or a denial.
   *
   * @param allowed true to let the command run
   * @param reason why it was denied; {@code null} when allowed
   */
  public Decision(final boolean allowed, final @Nullable String reason) {
    this(allowed, reason, null);
  }

  /**
   * Refuses the command.
   *
   * @param reason why, shown to the caller
   * @return a denying decision
   */
  public static Decision deny(final String reason) {
    return new Decision(false, reason);
  }

  /**
   * Lets the command run only once someone approves it. The review itself never waits: the server
   * parks the command and the caller is told that it awaits approval.
   *
   * @param reason why, shown to whoever approves; the server quotes it as data, never as an
   *     instruction
   * @param need who may approve, on which route and for how long
   * @return a decision that requires approval
   * @since 0.1.0
   */
  public static Decision requireApproval(final String reason, final ApprovalNeed need) {
    return new Decision(false, reason, Objects.requireNonNull(need, "need"));
  }

  /**
   * Whether the command needs approval rather than being denied.
   *
   * @return true if {@link #approval()} is set
   * @since 0.1.0
   */
  public boolean requiresApproval() {
    return approval != null;
  }
}
