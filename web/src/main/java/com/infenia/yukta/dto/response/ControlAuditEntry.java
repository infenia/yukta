// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One control command sent to a run or to the scheduler, and what became of it.
 *
 * @param time when it was recorded
 * @param executionId the run, or {@code scheduler}
 * @param command the command, e.g. {@code PauseWorkflowCommand} or {@code SetLimits(2, 0)}
 * @param actor who sent it ({@code key:<name>} or a supervisor id), or null for the single key or
 *     no key
 * @param applied whether it was applied; false if a reviewer or the run refused it
 * @param reason why it was refused, or null
 */
public record ControlAuditEntry(
    Instant time,
    String executionId,
    String command,
    @Nullable String actor,
    boolean applied,
    @Nullable String reason) {

  /** Log text: {@code command [by actor]} then, for a refusal, {@code : reason}. */
  private static final Pattern STATUS =
      Pattern.compile("^(.*?)(?: \\[by ([^\\]]+)\\])?(?:: (.*))?$");

  /**
   * Read an entry from a control event of the execution log.
   *
   * @param event a {@code CONTROL_APPLIED} or {@code CONTROL_REJECTED} event
   * @return the entry
   */
  public static ControlAuditEntry from(final ExecutionLogEvent event) {
    final Matcher match = STATUS.matcher(event.status() == null ? "" : event.status());
    match.matches();
    return new ControlAuditEntry(
        event.timestamp(),
        event.executionId(),
        match.group(1),
        match.group(2),
        event.type() == ExecutionLogEvent.Type.CONTROL_APPLIED,
        match.group(3));
  }
}
